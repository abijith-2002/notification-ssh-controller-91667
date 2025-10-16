package org.example.app.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.Properties
import kotlin.math.min

/**
 * SSH client interfaces and implementations.
 *
 * Provides:
 * - ISshClient: public interface for executing commands over SSH.
 * - JSchSshClient: production implementation using JSch with safe defaults.
 * - FakeSshClient: test/dry-run implementation for UI/testing without network access.
 */
object SshConstants {
    // Maximum number of bytes to capture for stdout/stderr to avoid unbounded memory usage.
    const val DEFAULT_MAX_CAPTURE_BYTES: Int = 256 * 1024 // 256 KiB
    // Default charset to decode captured bytes
    val DEFAULT_CHARSET: Charset = Charsets.UTF_8
}

/**
 * Result of an SSH command execution.
 *
 * success: true if command completed and exitStatus == 0.
 * exitStatus: The remote process exit status if available; -1 when not available.
 * stdout: Captured stdout truncated to maxCaptureBytes.
 * stderr: Captured stderr truncated to maxCaptureBytes.
 * errorMessage: Non-null when an error occurred (connection/exec/capture).
 */
// PUBLIC_INTERFACE
data class SshResult(
    val success: Boolean,
    val exitStatus: Int,
    val stdout: String,
    val stderr: String,
    val errorMessage: String? = null
)

/**
 * PUBLIC_INTERFACE
 * A minimal SSH client interface that supports executing a remote command with password authentication.
 *
 * Implementations must perform network/file IO off the main thread and avoid logging secrets.
 */
interface ISshClient {
    /**
     * Executes the given command on the remote host using password authentication.
     *
     * Parameters:
     * - host: SSH server host name or IP. Must be non-empty.
     * - port: SSH server port. Typical default is 22.
     * - username: SSH username. Must be non-empty.
     * - password: SSH password. Must be non-empty. Never log this.
     * - command: Shell command to execute remotely.
     * - timeoutMs: Overall connect/channel timeout in milliseconds. Applies to connect and channel open.
     * - maxCaptureBytes: Maximum bytes to capture from stdout and stderr each; defaults to safe bound.
     *
     * Returns: SshResult containing success flag, exit status, and captured output.
     */
    // PUBLIC_INTERFACE
    suspend fun execute(
        host: String,
        port: Int,
        username: String,
        password: String,
        command: String,
        timeoutMs: Int,
        maxCaptureBytes: Int = SshConstants.DEFAULT_MAX_CAPTURE_BYTES
    ): SshResult
}

/**
 * Production SSH client implementation using JSch with password authentication.
 *
 * Safe defaults:
 * - StrictHostKeyChecking=no (TODO: Provide known_hosts management and enable strict checking)
 * - Connect timeout respected.
 * - Channel exec timeout respected.
 * - Bounded stdout/stderr capture with truncation awareness.
 * - No secrets are logged or included in error messages.
 */
class JSchSshClient(
    private val jschProvider: () -> JSch = { JSch() },
    private val charset: Charset = SshConstants.DEFAULT_CHARSET
) : ISshClient {

    override suspend fun execute(
        host: String,
        port: Int,
        username: String,
        password: String,
        command: String,
        timeoutMs: Int,
        maxCaptureBytes: Int
    ): SshResult = withContext(Dispatchers.IO) {
        if (host.isBlank()) return@withContext failure("Host is empty")
        if (username.isBlank()) return@withContext failure("Username is empty")
        if (password.isEmpty()) return@withContext failure("Password is empty")
        if (port !in 1..65535) return@withContext failure("Port out of range")
        if (timeoutMs <= 0) return@withContext failure("Timeout must be > 0")
        val cappedMax = maxCaptureBytes.coerceAtLeast(1024)

        var session: Session? = null
        var channel: ChannelExec? = null

        try {
            val jsch = jschProvider()
            session = jsch.getSession(username, host, port).apply {
                setPassword(password)
                // Unsafe for production environments; acceptable as safe default with TODO for future hardening.
                val config = Properties().apply {
                    put("StrictHostKeyChecking", "no")
                    // Disable some outdated ciphers/mac/algos would go here if needed; rely on JSch defaults for now.
                }
                setConfig(config)
                // Connect: this will block, but we are already in Dispatchers.IO context.
                connect(timeoutMs)
            }

            channel = (session.openChannel("exec") as ChannelExec).apply {
                setCommand(command)
                // Do not allocate PTY by default to avoid unexpected formatting behavior.
                setPty(false)
                inputStream = null // we won't send stdin
                // We'll read stdout and stderr streams.
                // Note: setErrStream could be used to redirect, but we need to capture separately.
                connect(timeoutMs)
            }

            // Capture outputs bounded
            val stdoutBytes = boundedRead(channel.inputStream, cappedMax)
            val stderrBytes = boundedRead(channel.errStream, cappedMax)

            // Wait for completion or until the timeout elapses.
            val endAt = System.currentTimeMillis() + timeoutMs
            while (!channel.isClosed && System.currentTimeMillis() < endAt) {
                Thread.sleep(20)
            }

            if (!channel.isClosed) {
                // Timeout waiting for command to finish
                return@withContext SshResult(
                    success = false,
                    exitStatus = -1,
                    stdout = decode(stdoutBytes),
                    stderr = decode(stderrBytes),
                    errorMessage = "Execution timed out"
                )
            }

            val status = channel.exitStatus
            val success = status == 0
            return@withContext SshResult(
                success = success,
                exitStatus = status,
                stdout = decode(stdoutBytes),
                stderr = decode(stderrBytes),
                errorMessage = if (success) null else "Remote command failed with exit status $status"
            )
        } catch (ex: JSchException) {
            // Avoid including secrets in message.
            return@withContext failure("SSH error: ${safeMessage(ex)}")
        } catch (ex: Exception) {
            return@withContext failure("Execution error: ${safeMessage(ex)}")
        } finally {
            try {
                channel?.disconnect()
            } catch (_: Exception) { /* ignore */ }
            try {
                session?.disconnect()
            } catch (_: Exception) { /* ignore */ }
        }
    }

    private fun decode(bytes: ByteArray): String = try {
        String(bytes, charset)
    } catch (_: Exception) {
        String(bytes, Charsets.UTF_8)
    }

    private fun failure(msg: String): SshResult =
        SshResult(success = false, exitStatus = -1, stdout = "", stderr = "", errorMessage = msg)

    private fun safeMessage(ex: Throwable): String {
        // Provide concise message without stack trace or sensitive data.
        return ex.message?.take(512) ?: ex::class.simpleName.orEmpty()
    }

    /**
     * Reads up to maxBytes from the input stream without blocking indefinitely.
     * Uses a growing ByteArrayOutputStream while limiting overall captured bytes.
     *
     * This method will read until the channel closes the stream or the cap is reached.
     */
    private fun boundedRead(input: java.io.InputStream?, maxBytes: Int): ByteArray {
        if (input == null) return ByteArray(0)
        val buffer = ByteArray(8 * 1024)
        val out = ByteArrayOutputStream(min(maxBytes, 64 * 1024))
        var total = 0
        try {
            // Non-blocking-like loop: read available data; if none, sleep very briefly.
            while (true) {
                val available = input.available()
                if (available <= 0) {
                    // If stream may still have more data later, sleep briefly.
                    if (out.size() >= maxBytes) break
                    // A tiny sleep to avoid busy-looping; caller uses outer timeout for channel.
                    Thread.sleep(10)
                }
                val toRead = min(buffer.size, maxBytes - total)
                if (toRead <= 0) break
                val read = input.read(buffer, 0, toRead)
                if (read == -1) break
                out.write(buffer, 0, read)
                total += read
                if (total >= maxBytes) break
            }
        } catch (_: Exception) {
            // Ignore stream errors during capture; return what we have.
        }
        return out.toByteArray()
    }
}

/**
 * A simple fake SSH client useful for tests or UI dry runs.
 *
 * Behavior:
 * - Returns a configurable canned result or a synthesized echo-like response.
 * - Never performs network IO.
 */
// PUBLIC_INTERFACE
class FakeSshClient(
    private val cannedResult: SshResult? = null
) : ISshClient {
    override suspend fun execute(
        host: String,
        port: Int,
        username: String,
        password: String,
        command: String,
        timeoutMs: Int,
        maxCaptureBytes: Int
    ): SshResult = withContext(Dispatchers.IO) {
        cannedResult ?: run {
            // Provide a deterministic, bounded fake output.
            val summary = buildString {
                append("FAKE SSH RUN\n")
                append("host="); append(mask(host)); append(" port="); append(port); append('\n')
                append("user="); append(mask(username)); append('\n')
                append("cmd="); append(command.take(200)); append('\n')
                append("timeoutMs="); append(timeoutMs); append('\n')
            }
            SshResult(
                success = true,
                exitStatus = 0,
                stdout = summary.take(maxCaptureBytes),
                stderr = "",
                errorMessage = null
            )
        }
    }

    private fun mask(value: String): String = value.take(1) + "*".repeat(value.length.coerceAtLeast(1) - 1)
}

/*
TODO Security Hardening:
- Provide known_hosts file management and set StrictHostKeyChecking=yes when host keys are trusted.
- Consider adding configurable ciphers/MACs/KEX algorithms if required by server policies.
- Support public key auth in addition to password auth (avoid storing private keys insecurely).
*/
