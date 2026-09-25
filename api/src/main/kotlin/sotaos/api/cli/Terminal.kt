package sotaos.api.cli

import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

internal fun newPassphrase(): CharArray {
    val password = readSecret("Нова парольна фраза (12–1024 символи): ")
    var repeated: CharArray? = null
    var confirmed = false
    try {
        repeated = readSecret("Повторіть парольну фразу: ")
        require(password.contentEquals(repeated)) { "Парольні фрази не збігаються." }
        confirmed = true
        return password
    } finally {
        if (!confirmed) password.fill('\u0000')
        repeated?.fill('\u0000')
    }
}

internal fun readSecret(prompt: String): CharArray {
    System.console()?.let { return it.readPassword("%s", prompt) ?: error("Не вдалося прочитати парольну фразу.") }
    val tty = File("/dev/tty")
    require(tty.exists()) { "Для входу потрібен інтерактивний термінал; пароль не передається аргументом CLI." }
    print(prompt); System.out.flush()
    setTtyEcho(tty, false)
    return try {
        BufferedReader(InputStreamReader(FileInputStream(tty),
            StandardCharsets.UTF_8)).use { it.readLine()?.toCharArray() }
            ?: error("Не вдалося прочитати парольну фразу.")
    } finally {
        setTtyEcho(tty, true)
        println()
    }
}

internal fun readTerminalLine(prompt: String): String {
    System.console()?.let { return it.readLine("%s", prompt).orEmpty() }
    val tty = File("/dev/tty")
    require(tty.exists()) { "Для цієї дії потрібен інтерактивний термінал." }
    print(prompt); System.out.flush()
    return BufferedReader(InputStreamReader(FileInputStream(tty),
        StandardCharsets.UTF_8)).use { it.readLine().orEmpty() }
}

private fun setTtyEcho(tty: File, enabled: Boolean) {
    val mode = if (enabled) "echo" else "-echo"
    val result = ProcessBuilder("stty", mode)
        .redirectInput(tty)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
        .waitFor()
    check(result == 0) { "Could not update terminal echo mode." }
}
