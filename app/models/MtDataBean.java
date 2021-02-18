package models;

import com.github.s7connector.api.annotation.S7Variable;
import com.github.s7connector.impl.utils.S7Type;

public class MtDataBean {
    @S7Variable(byteOffset = 0, bitOffset = 0, type = S7Type.REAL)
    public float value;

    @Override
    public String toString() {
        return String.valueOf(value);
    }
}
