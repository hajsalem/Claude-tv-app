package app.duckblast;

/**
 * Wire protocol between the phone remote and the TV game: one TCP connection,
 * newline-terminated text lines.
 *
 * Phone -> TV
 *   HELLO <name>        first line after connecting
 *   AIM <x> <y>         aim point, 0..1 across/down the screen (0.5 0.5 = centre)
 *   FIRE <x> <y>        trigger pulled at that aim point
 *   CAL                 player re-centred their aim
 *   PING
 *
 * TV -> Phone
 *   WELCOME <n> <argb>  you are player n, crosshair colour
 *   FULL                game already has the maximum number of players
 *   STAT <score> <ammo> current score and shells left (-1 = unlimited)
 *   HIT <points>        your shot hit something
 *   EMPTY               you pulled the trigger with no shells left
 *   MSG <text>          status line to show on the phone
 */
final class Net {
    static final int PORT = 47823;
    static final String SERVICE_TYPE = "_duckblast._tcp";
    static final String SERVICE_NAME = "Duck Blast";

    private Net() {}

    static float parseF(String s, float def) {
        try {
            return Float.parseFloat(s);
        } catch (RuntimeException e) {
            return def;
        }
    }

    static int parseI(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (RuntimeException e) {
            return def;
        }
    }
}
