package models;

import com.github.s7connector.api.annotation.S7Variable;
import com.github.s7connector.impl.utils.S7Type;

public class Bit2Bean {
    @S7Variable(byteOffset = 0, bitOffset = 2, type = S7Type.BOOL)
    public boolean value;

    @Override
    public String toString() {
        return Boolean.toString(value);
    }
}
