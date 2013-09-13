package tripod.iqc.core;

import java.io.IOException;

public interface Reader {
    Sample read () throws IOException;
}
