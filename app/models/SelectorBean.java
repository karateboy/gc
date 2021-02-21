package models;

import com.github.s7connector.api.annotation.S7Variable;
import com.github.s7connector.impl.utils.S7Type;

public class SelectorBean {
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

    @S7Variable(byteOffset = 0, bitOffset = 8, type = S7Type.BOOL)
    public boolean p9;

    @S7Variable(byteOffset = 0, bitOffset = 9, type = S7Type.BOOL)
    public boolean p10;

    @S7Variable(byteOffset = 0, bitOffset = 10, type = S7Type.BOOL)
    public boolean p11;

    @S7Variable(byteOffset = 0, bitOffset = 11, type = S7Type.BOOL)
    public boolean p12;

    @Override
    public String toString() {
        return Integer.toString(getPos(), 2);
    }
    public int getPos() {
        int v = 0;
        if(p1)
            v |=0x1;
        if(p2)
            v |=0x2;
        if(p3)
            v |=0x4;
        if(p4)
            v |=0x8;
        if(p5)
            v |=0x10;
        if(p6)
            v |=0x20;
        if(p7)
            v |=0x40;
        if(p8)
            v |=0x80;
        if(p9)
            v |=0x100;
        if(p10)
            v |=0x200;
        if(p11)
            v |=0x400;
        if(p12)
            v |=0x800;

        return v;
    }
}
