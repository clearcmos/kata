package com.clearcmos.kata.actions

import android.content.Context
import android.util.Log
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.KeyPair
import java.io.ByteArrayOutputStream
import java.io.File

/** What an SSH command did. [exitStatus] is -1 when the channel closed without reporting one. */
data class SshResult(val exitStatus: Int, val output: String)

/**
 * Runs one command on a remote host.
 *
 * The key is generated on the device on first use and never leaves it; only the public half is
 * exported, to the app's external files directory where `adb shell` can read it. That is the
 * whole setup: no key is typed in, pasted, or committed.
 *
 * ECDSA rather than ed25519 on purpose. jsch ships as a multi-release jar whose ed25519 support
 * lives in the Java 15+ classes, and Android ignores META-INF/versions entirely, so only the
 * Java 8 base is present at runtime.
 */
class SshClient(private val context: Context) {
    private val sshDir = File(context.filesDir, "ssh")
    private val privateKey = File(sshDir, "id_ecdsa")
    private val publicKey = File(sshDir, "id_ecdsa.pub")
    private val knownHosts = File(sshDir, "known_hosts")

    /** Generates the keypair if absent, and returns the public key line. */
    fun ensureKey(): String {
        if (!privateKey.exists()) {
            sshDir.mkdirs()
            val pair = KeyPair.genKeyPair(JSch(), KeyPair.ECDSA, KEY_BITS)
            pair.writePrivateKey(privateKey.absolutePath)
            pair.writePublicKey(publicKey.absolutePath, COMMENT)
            pair.dispose()
            privateKey.setReadable(false, false)
            privateKey.setReadable(true, true)
            Log.i(TAG, "generated ssh key at ${privateKey.absolutePath}")
        }
        val line = publicKey.readText().trim()
        mirrorPublicKey(line)
        return line
    }

    /** Puts the public key where `adb shell cat` can reach it, so setup needs no UI. */
    private fun mirrorPublicKey(line: String) {
        val external = context.getExternalFilesDir(null) ?: return
        runCatching {
            val target = File(external, "id_ecdsa.pub")
            if (!target.exists() || target.readText().trim() != line) target.writeText(line + "\n")
        }.onFailure { Log.w(TAG, "could not mirror public key", it) }
    }

    fun run(host: String, user: String, port: Int, command: String, timeoutMs: Int): SshResult {
        ensureKey()
        if (!knownHosts.exists()) {
            sshDir.mkdirs()
            knownHosts.createNewFile()
        }

        val jsch = JSch()
        jsch.addIdentity(privateKey.absolutePath)
        jsch.setKnownHosts(knownHosts.absolutePath)

        val session = jsch.getSession(user, host, port)
        // Trust on first use, then pinned: the first connection records the host key and a later
        // change is refused. Prompting is not an option with no user present.
        session.setConfig("StrictHostKeyChecking", "accept-new")
        session.setConfig("PreferredAuthentications", "publickey")

        try {
            session.connect(timeoutMs)
        } catch (e: JSchException) {
            throw ActionError(describeConnectFailure(host, user, port, e))
        }

        try {
            val channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(command)
            val captured = ByteArrayOutputStream()
            channel.outputStream = captured
            channel.setErrStream(captured)
            channel.connect(timeoutMs)

            val deadline = System.currentTimeMillis() + timeoutMs
            while (!channel.isClosed && System.currentTimeMillis() < deadline) {
                Thread.sleep(POLL_MS)
            }
            val timedOut = !channel.isClosed
            val status = if (timedOut) -1 else channel.exitStatus
            channel.disconnect()
            if (timedOut) {
                throw ActionError("$user@$host: '$command' was still running after ${timeoutMs}ms")
            }
            return SshResult(status, captured.toString(Charsets.UTF_8.name()).trim())
        } finally {
            session.disconnect()
        }
    }

    /**
     * jsch reports every failure as one exception type, so the message is the only signal. An
     * unreachable host and a rejected key need completely different fixes, and the run log is
     * where that distinction has to survive.
     */
    private fun describeConnectFailure(host: String, user: String, port: Int, e: JSchException): String {
        val detail = e.message.orEmpty()
        return when {
            detail.contains("Auth fail", ignoreCase = true) ||
                detail.contains("Auth cancel", ignoreCase = true) ->
                "$user@$host refused kata's key. Add this line to ~/.ssh/authorized_keys there:\n" +
                    ensureKey()

            detail.contains("HostKey", ignoreCase = true) || detail.contains("reject", ignoreCase = true) ->
                "$host presented a different host key than the one kata recorded. If the machine was " +
                    "rebuilt, clear ${knownHosts.absolutePath} and let it pin again."

            else ->
                "could not reach $user@$host:$port ($detail). The machine is probably off or not " +
                    "on this network."
        }
    }

    private companion object {
        const val TAG = "KataSsh"
        const val KEY_BITS = 256
        const val POLL_MS = 50L
        const val COMMENT = "kata@android"
    }
}
