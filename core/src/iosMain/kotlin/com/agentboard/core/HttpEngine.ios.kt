package com.agentboard.core

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.dataUsingEncoding
import platform.Foundation.setHTTPBody
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait

/**
 * Board HTTP on iOS through NSURLSession, the system networking stack (so it follows the phone's
 * proxy, VPN and certificate settings). Blocking like the JVM client: call it from a background
 * dispatcher, never the main thread. Same 15 s limit as the other clients.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal actual object HttpEngine {
    actual fun get(url: String): String = send("GET", url, null)

    actual fun post(url: String, body: String): String = send("POST", url, body)

    private fun send(method: String, url: String, body: String?): String {
        val nsUrl = NSURL.URLWithString(url) ?: throw IllegalStateException("Not a URL: $url")
        val request = NSMutableURLRequest.requestWithURL(nsUrl).apply {
            setHTTPMethod(method)
            setTimeoutInterval(15.0)
            setValue("application/json", forHTTPHeaderField = "Accept")
            // Free ngrok tunnels answer browsers with a warning page unless this header is present.
            setValue("1", forHTTPHeaderField = "ngrok-skip-browser-warning")
            if (body != null) {
                setValue("application/json", forHTTPHeaderField = "Content-Type")
                setHTTPBody(NSString.create(string = body).dataUsingEncoding(NSUTF8StringEncoding))
            }
        }
        val done = dispatch_semaphore_create(0)
        var text: String? = null
        var status = 0
        var failure: String? = null
        NSURLSession.sharedSession.dataTaskWithRequest(request) { data: NSData?, response, error ->
            status = (response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 0
            text = data?.let { NSString.create(data = it, encoding = NSUTF8StringEncoding)?.toString() }
            failure = error?.localizedDescription
            dispatch_semaphore_signal(done)
        }.resume()
        dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER)
        failure?.let { throw IllegalStateException("$method $url: $it") }
        check(status in 200..299) { "$method $url returned $status: ${text?.take(200)}" }
        return text.orEmpty()
    }
}
