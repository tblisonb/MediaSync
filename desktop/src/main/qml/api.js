// Calls to the desktop service's window API (WindowApi.kt), as JSON.
.pragma library

/** Reads a local file; QML_XHR_ALLOW_FILE_READ=1 lets the window do this. */
function readFile(path, done, failed) {
    const xhr = new XMLHttpRequest()
    xhr.onreadystatechange = function () {
        if (xhr.readyState !== XMLHttpRequest.DONE) return
        const text = (xhr.responseText || "").trim()
        if (text) done(text); else failed()
    }
    xhr.open("GET", "file://" + path)
    xhr.send()
}

/** [method] [url], with [body] as JSON if given; [done] gets the parsed reply, [failed] a message. */
function call(method, url, body, done, failed) {
    const xhr = new XMLHttpRequest()
    xhr.onreadystatechange = function () {
        if (xhr.readyState !== XMLHttpRequest.DONE) return
        if (xhr.status === 200) {
            let value
            try {
                value = JSON.parse(xhr.responseText)
            } catch (e) {
                failed("The service sent something unexpected.", false)
                return
            }
            done(value)
        } else {
            // 0: nothing answered (the service stopped, or restarted at a new address).
            failed(xhr.status === 0 ? "The MediaSync service isn't running." : "The service answered " + xhr.status + ".", xhr.status === 0 || xhr.status === 404)
        }
    }
    xhr.open(method, url)
    if (body !== undefined) xhr.setRequestHeader("Content-Type", "application/json")
    xhr.send(body === undefined ? null : JSON.stringify(body))
}
