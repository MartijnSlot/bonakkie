package web

import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Serves a built single page app from [directory]. Existing files are served as they are. Other paths that don't
 * look like files get `index.html`, so client-side routes work on reload. The API is mapped under `/api` separately
 * and never reaches this servlet.
 */
class SpaServlet(directory: Path) : HttpServlet() {
    private val root: Path = directory.toAbsolutePath().normalize()
    private val index: Path = root.resolve("index.html")

    override fun doGet(request: HttpServletRequest, response: HttpServletResponse) {
        val relative = (request.pathInfo ?: "/").removePrefix("/")
        val file = resolve(relative)
        val target = when {
            file != null && Files.isRegularFile(file) -> file
            // A missing asset (e.g. /app.js) is a real 404, not a client-side route.
            relative.substringAfterLast('/').contains('.') -> null
            else -> index
        }
        if (target == null || !Files.isRegularFile(target)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND)
            return
        }
        response.contentType = servletContext.getMimeType(target.fileName.toString()) ?: "application/octet-stream"
        // index.html must always be revalidated so a new deployment is picked up.
        if (target == index) response.setHeader("Cache-Control", "no-cache")
        response.setContentLengthLong(Files.size(target))
        Files.copy(target, response.outputStream)
    }

    /** The file for [relative], or null if the path is invalid or escapes the app directory. */
    private fun resolve(relative: String): Path? = try {
        root.resolve(relative).normalize().takeIf { it.startsWith(root) }
    } catch (e: InvalidPathException) {
        null
    }
}
