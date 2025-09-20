package com.ykkap.lockbridge.http

import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.html.respondHtml
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.html.*
import java.util.concurrent.atomic.AtomicReference

class WebServerManager(
  private val lockStatus: Flow<String>,
  private val lastStatusUpdateTime: Flow<Long?>,
  private val onCommand: (command: String) -> Unit
) {
  private val server = AtomicReference<EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>?>(null)

  companion object {
    private const val TAG = "WebServerManager"
  }

  fun start(port: Int) {
    if (server.get() != null) {
      Log.w(TAG, "Server is already running. Stopping it before starting a new one.")
      stop()
    }

    val newServer = embeddedServer(CIO, port, module = {
      install(StatusPages) {
        exception<Throwable> { call, cause ->
          Log.e(TAG, "Unhandled exception in Ktor server", cause)
          call.respondText(
            "Internal Server Error: ${cause.localizedMessage}",
            ContentType.Text.Plain,
            HttpStatusCode.InternalServerError
          )
        }
      }
      routing {
        get("/") {
          call.respondHtml {
            head {
              title("Lock Control")
              meta {
                name = "viewport"
                content = "width=device-width, initial-scale=1.0"
              }
              style {
                unsafe {
                  +"""
                    body { font-family: sans-serif; display: flex; flex-direction: column; align-items: center; justify-content: center; height: 100vh; margin: 0; background-color: #f0f0f0; text-align: center; }
                    h1 { color: #333; }
                    .status-container { margin-bottom: 20px; font-size: 1.2rem; }
                    #status { font-weight: bold; }
                    #last-updated { color: #666; font-size: 0.9rem; }
                    .button-container { display: flex; flex-direction: row; gap: 20px; }
                    button { font-size: 1.5rem; padding: 20px 40px; border-radius: 8px; border: none; color: white; cursor: pointer; width: 150px; }
                    .lock { background-color: #d9534f; }
                    .unlock { background-color: #5cb85c; }
                  """.trimIndent()
                }
              }
            }
            body {
              h1 { +"Door Lock Control" }
              div(classes = "status-container") {
                p {
                  +"Status: "
                  span { id = "status" }
                }
                p {
                  +"Last Updated: "
                  span { id = "last-updated" }
                }
              }
              div(classes = "button-container") {
                postForm(action = "/lock", encType = FormEncType.applicationXWwwFormUrlEncoded) {
                  button(type = ButtonType.submit, classes = "lock") { +"Lock" }
                }
                postForm(action = "/unlock", encType = FormEncType.applicationXWwwFormUrlEncoded) {
                  button(type = ButtonType.submit, classes = "unlock") { +"Unlock" }
                }
              }
              script {
                unsafe {
                  +"""
                    const statusEl = document.getElementById('status');
                    const lastUpdatedEl = document.getElementById('last-updated');
                    let lastUpdatedTimestamp = 0;
                    let timeUpdateInterval;

                    function updateRelativeTime() {
                        if (lastUpdatedTimestamp === 0 || lastUpdatedTimestamp === null) {
                            lastUpdatedEl.textContent = 'never';
                            return;
                        }
                        const now = Date.now();
                        const secondsAgo = Math.round((now - lastUpdatedTimestamp) / 1000);
                        if (secondsAgo < 0) {
                            lastUpdatedEl.textContent = 'just now';
                        } else if (secondsAgo < 60) {
                            lastUpdatedEl.textContent = `${"$"}{secondsAgo} second${"$"}{secondsAgo === 1 ? '' : 's'} ago`;
                        } else {
                            const minutes = Math.floor(secondsAgo / 60);
                            lastUpdatedEl.textContent = `${"$"}{minutes} minute${"$"}{minutes === 1 ? '' : 's'} ago`;
                        }
                    }

                    async function fetchStatus() {
                        try {
                            const response = await fetch('/status');
                            if (!response.ok) {
                                statusEl.textContent = 'Error';
                                lastUpdatedEl.textContent = 'N/A';
                                return;
                            }
                            const data = await response.json();

                            statusEl.textContent = data.status;
                            if (data.status === 'LOCKED') {
                                statusEl.style.color = '#d9534f'; // Red
                            } else if (data.status === 'UNLOCKED') {
                                statusEl.style.color = '#5cb85c'; // Green
                            } else {
                                statusEl.style.color = '#f0ad4e'; // Orange
                            }

                            if (data.lastUpdated !== lastUpdatedTimestamp) {
                                lastUpdatedTimestamp = data.lastUpdated;
                                if (timeUpdateInterval) clearInterval(timeUpdateInterval);
                                
                                if (lastUpdatedTimestamp) {
                                    updateRelativeTime();
                                    timeUpdateInterval = setInterval(updateRelativeTime, 1000);
                                } else {
                                   lastUpdatedEl.textContent = 'never';
                                }
                            }
                        } catch (error) {
                            console.error('Failed to fetch status:', error);
                            statusEl.textContent = 'Offline';
                        }
                    }

                    document.addEventListener('DOMContentLoaded', () => {
                        fetchStatus();
                        setInterval(fetchStatus, 5000);
                    });
                  """.trimIndent()
                }
              }
            }
          }
        }
        get("/status") {
          val status = lockStatus.first()
          val timestamp = lastStatusUpdateTime.first()
          // Ktor doesn't have a built-in JSON DSL without extra plugins,
          // so we construct a simple JSON string manually.
          val jsonResponse = """{"status": "$status", "lastUpdated": $timestamp}"""
          call.respondText(jsonResponse, ContentType.Application.Json)
        }
        post("/lock") {
          Log.i(TAG, "Received /lock command from web.")
          onCommand("LOCK")
          call.respondRedirect("/")
        }
        post("/unlock") {
          Log.i(TAG, "Received /unlock command from web.")
          onCommand("UNLOCK")
          call.respondRedirect("/")
        }
      }
    })
    newServer.start(wait = false)
    server.set(newServer)
    Log.i(TAG, "Web server started on port $port")
  }

  fun stop() {
    server.getAndSet(null)?.stop(1000, 2000)
    Log.i(TAG, "Web server stopped.")
  }
}
