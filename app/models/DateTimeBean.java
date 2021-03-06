package models;

import com.github.s7connector.api.annotation.S7Variable;
import com.github.s7connector.impl.utils.S7Type;

import java.util.Date;

public class DateTimeBean {
    @S7Variable(byteOffset = 0, bitOffset = 0, type = S7Type.DATE_AND_TIME)
    public Date value;

    @Override
    public String toString() {
        return value.toString();
    }
}
