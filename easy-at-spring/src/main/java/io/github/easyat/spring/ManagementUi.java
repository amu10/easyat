package io.github.easyat.spring;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Minimal read-only management console shipped with the starters. The page is served as a static
 * HTML document and contains no secrets; every data read and every manual retry/rollback goes
 * through the token-gated REST endpoints in {@link ManagementService}, so sensitive undo columns
 * are rendered with the configured {@code UndoDataMasker} applied.
 */
public final class ManagementUi {
    private ManagementUi() {}

    private static final String HTML = load();

    /**
     * @return the management console HTML, or a minimal fallback if the resource is missing.
     */
    public static String page() {
        return HTML;
    }

    private static String load() {
        try (InputStream in = ManagementUi.class.getResourceAsStream("/management-ui.html")) {
            if (in == null)
                return "<html><body>easyAt management UI resource missing</body></html>";
            return new String(readAll(in), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "<html><body>easyAt management UI unavailable</body></html>";
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
