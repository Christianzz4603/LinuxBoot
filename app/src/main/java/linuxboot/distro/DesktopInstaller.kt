package linuxboot.distro

import java.security.SecureRandom

/** Per-distribution desktop installation, run inside the rootfs under PRoot. */
object DesktopInstaller {
    /** XFCE plus its dependencies is large; require headroom before starting. */
    const val REQUIRED_FREE = 1_500L * 1024 * 1024

    fun supports(distroId: String) = distroId == "alpine"

    /** VNC passwords are limited to 8 characters; this one is random per installation. */
    fun randomPassword(): String {
        val chars = "abcdefghijkmnpqrstuvwxyz23456789"
        val r = SecureRandom()
        return (1..8).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }

    /** Null if the distribution has no verified desktop recipe. */
    fun script(distroId: String, password: String): String? = when (distroId) {
        "alpine" -> listOf(
            "set -e",
            "apk update",
            "apk add --no-cache --no-progress xfce4 xfce4-terminal tigervnc dbus dbus-x11 font-dejavu adwaita-icon-theme",
            "dbus-uuidgen --ensure=/etc/machine-id",
            "mkdir -p /root/.vnc",
            "echo '$password' | vncpasswd -f > /root/.vnc/passwd",
            "chmod 600 /root/.vnc/passwd",
            "echo DESKTOP_INSTALL_OK",
        ).joinToString(System.lineSeparator())
        else -> null
    }
}
