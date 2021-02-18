package models;

import com.github.s7connector.api.annotation.S7Variable;
import com.github.s7connector.impl.utils.S7Type;

public class SelectorBean {
    @S7Variable(byteOffset = 0, bitOffset = 0, type = S7Type.WORD)
    public int value;

    @Override
    public String toString() {
        return "0b" + Integer.toString(value, 2);
    }
    public int getPos() {
        for(int i = 0;i<16;i++){
            if((value & 1<<i) != 0)
                return i;
        }
        return 0;
    }
}
