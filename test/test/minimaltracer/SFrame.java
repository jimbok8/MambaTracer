/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package test.minimaltracer;

import cl.core.data.CPoint3;
import coordinate.struct.ByteStruct;

/**
 *
 * @author user
 */
public class SFrame extends ByteStruct{
    public CPoint3 mX;
    public CPoint3 mY;
    public CPoint3 mZ;
    
    public SFrame()
    {
        mX = new CPoint3();
        mY = new CPoint3();
        mZ = new CPoint3();
    }
}
