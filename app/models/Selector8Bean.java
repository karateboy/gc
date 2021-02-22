package models;

import com.github.s7connector.api.annotation.S7Variable;
import com.github.s7connector.impl.utils.S7Type;

public class Selector8Bean {
    @S7Variable(byteOffset = 0, bitOffset = 0, type = S7Type.BOOL)
    public boolean p1;

    @S7Variable(byteOffset = 0, bitOffset = 1, type = S7Type.BOOL)
    public boolean p2;

    @S7Variable(byteOffset = 0, bitOffset = 2, type = S7Type.BOOL)
    public boolean p3;

    @S7Variable(byteOffset = 0, bitOffset = 3, type = S7Type.BOOL)
    public boolean p4;

    @S7Variable(byteOffset = 0, bitOffset = 4, type = S7Type.BOOL)
    public boolean p5;

    @S7Variable(byteOffset = 0, bitOffset = 5, type = S7Type.BOOL)
    public boolean p6;

    @S7Variable(byteOffset = 0, bitOffset = 6, type = S7Type.BOOL)
    public boolean p7;

    @S7Variable(byteOffset = 0, bitOffset = 7, type = S7Type.BOOL)
    public boolean p8;

    @Override
    public String toString() {
        return Integer.toString(getPos(), 2);
    }
    public int getPos() {
        int v = 0;
        if(p1)
            v =1;
        if(p2)
            v =2;
        if(p3)
            v =3;
        if(p4)
            v =4;
        if(p5)
            v =5;
        if(p6)
            v =6;
        if(p7)
            v =7;
        if(p8)
            v =8;

        return v;
    }
}
