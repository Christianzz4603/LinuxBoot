package linuxboot.distro

import java.security.SecureRandom

/** Per-distribution desktop installation, run inside the rootfs under PRoot. */
object DesktopInstaller {
    /** XFCE plus its dependencies is large; require headroom before starting. */
    const val REQUIRED_FREE = 1_500L * 1024 * 1024

    fun supports(distroId: String) = distroId in setOf("alpine", "debian", "ubuntu")

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
        // apt normally drops to an "_apt" user, which does not work under PRoot; stay root.
        "debian", "ubuntu" -> listOf(
            "set -e",
            "export DEBIAN_FRONTEND=noninteractive",
            "echo 'APT::Sandbox::User \"root\";' > /etc/apt/apt.conf.d/99linuxboot-proot",
            "apt-get update",
            "apt-get install -y --no-install-recommends xfce4 xfce4-terminal tigervnc-standalone-server " +
                "tigervnc-common dbus-x11 fonts-dejavu-core",
            "dbus-uuidgen --ensure=/etc/machine-id",
            "mkdir -p /root/.vnc",
            "echo '$password' | (vncpasswd -f || tigervncpasswd -f) > /root/.vnc/passwd",
            "chmod 600 /root/.vnc/passwd",
            "echo DESKTOP_INSTALL_OK",
        ).joinToString(System.lineSeparator())
        else -> null
    }
}
