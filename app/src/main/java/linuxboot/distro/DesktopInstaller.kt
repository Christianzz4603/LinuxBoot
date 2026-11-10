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

    /** Steps shared by every distribution, ending with checks that fail loudly if something is missing. */
    private fun finish(password: String): List<String> = listOf(
        // Some images ship a placeholder machine-id (Debian: 13 characters) that dbus-uuidgen refuses to replace.
        "rm -f /etc/machine-id /var/lib/dbus/machine-id",
        "dbus-uuidgen --ensure=/etc/machine-id",
        "mkdir -p /var/lib/dbus /root/.vnc",
        "ln -sf /etc/machine-id /var/lib/dbus/machine-id",
        "echo '$password' | (vncpasswd -f || tigervncpasswd -f) > /root/.vnc/passwd",
        "chmod 600 /root/.vnc/passwd",
        "command -v startxfce4 >/dev/null || { echo MISSING: startxfce4; exit 1; }",
        "command -v dbus-run-session >/dev/null || { echo MISSING: dbus-run-session; exit 1; }",
        "command -v Xvnc >/dev/null || command -v Xtigervnc >/dev/null || { echo MISSING: Xvnc or Xtigervnc; exit 1; }",
        "test -s /root/.vnc/passwd || { echo MISSING: VNC password file; exit 1; }",
        "echo DESKTOP_INSTALL_OK",
    )

    /** Null if the distribution has no verified desktop recipe. Safe to run again after a failure. */
    fun script(distroId: String, password: String): String? = when (distroId) {
        "alpine" -> (
            listOf(
                "set -e",
                "apk update",
                "apk add --no-cache --no-progress xfce4 xfce4-terminal tigervnc dbus dbus-x11 font-dejavu adwaita-icon-theme",
            ) + finish(password)
            ).joinToString(System.lineSeparator())

        // apt normally drops to an "_apt" user, which does not work under PRoot; stay root.
        // xfonts-base and x11-xkb-utils are Recommends: without them the X server can fail to start.
        "debian", "ubuntu" -> (
            listOf(
                "set -e",
                "export DEBIAN_FRONTEND=noninteractive",
                "echo 'APT::Sandbox::User \"root\";' > /etc/apt/apt.conf.d/99linuxboot-proot",
                "apt-get update",
                "apt-get install -y --no-install-recommends xfce4 xfce4-terminal tigervnc-standalone-server " +
                    "tigervnc-common dbus-x11 fonts-dejavu-core xfonts-base x11-xkb-utils xkb-data",
            ) + finish(password)
            ).joinToString(System.lineSeparator())

        else -> null
    }
}
