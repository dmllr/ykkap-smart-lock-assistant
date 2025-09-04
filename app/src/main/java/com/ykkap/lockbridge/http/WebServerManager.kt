package com.ykkap.lockbridge.http

import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
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
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.meta
import kotlinx.html.postForm
import kotlinx.html.style
import kotlinx.html.title
import kotlinx.html.unsafe
import java.util.concurrent.atomic.AtomicReference

class WebServerManager(
  private val onCommand: (command: String) -> Unit
) {
  // Use the specific type returned by embeddedServer to avoid casting issues.
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
                    body { font-family: sans-serif; display: flex; flex-direction: column; align-items: center; justify-content: center; height: 100vh; margin: 0; background-color: #f0f0f0; }
                    h1 { color: #333; }
                    button { font-size: 1.5rem; padding: 20px 40px; margin: 10px; border-radius: 8px; border: none; color: white; cursor: pointer; width: 200px; }
                    .lock { background-color: #d9534f; }
                    .unlock { background-color: #5cb85c; }
                  """.trimIndent()
                }
              }
            }
            body {
              h1 { +"Door Lock Control" }
              postForm(action = "/lock", encType = kotlinx.html.FormEncType.applicationXWwwFormUrlEncoded) {
                button(type = kotlinx.html.ButtonType.submit, classes = "lock") { +"Lock" }
              }
              postForm(action = "/unlock", encType = kotlinx.html.FormEncType.applicationXWwwFormUrlEncoded) {
                button(type = kotlinx.html.ButtonType.submit, classes = "unlock") { +"Unlock" }
              }
            }
          }
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
    // This works because EmbeddedServer implements the ApplicationEngine interface, which has the stop() method.
    server.getAndSet(null)?.stop(1000, 2000)
    Log.i(TAG, "Web server stopped.")
  }
}
