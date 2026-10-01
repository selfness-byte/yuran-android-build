package com.gongyo.orderly;

import java.util.List;
import java.util.ArrayList;

/** Places already measured rows intact. An unprintable row is an error, never a continuation. */
final class PagePlanner {
    static final class RowPlacement {
        final int row;
        final float height;
        RowPlacement(int row,float height) { this.row=row;this.height=height; }
    }
    static List<List<RowPlacement>> planRows(float[] rowHeights,float availableHeight) {
        if(rowHeights==null || !finitePositive(availableHeight)) throw invalidArea();
        // Validate every row before returning any page or drawing/writing output.
        for(float height:rowHeights) if(!finitePositive(height) || height>availableHeight)
            throw new IllegalArgumentException("품목 한 행이 인쇄 영역보다 큽니다. 품번·색상 길이와 A4 여백을 확인해 주세요.");
        List<List<RowPlacement>> pages=new ArrayList<List<RowPlacement>>();
        List<RowPlacement> page=new ArrayList<RowPlacement>();double used=0;
        float rounding=Math.ulp(availableHeight)*2;
        for(int row=0;row<rowHeights.length;row++) {
            float height=rowHeights[row];
            if(!page.isEmpty() && used+height>availableHeight+rounding) {
                pages.add(page);page=new ArrayList<RowPlacement>();used=0;
            }
            page.add(new RowPlacement(row,height));used+=height;
        }
        if(!page.isEmpty())pages.add(page);
        return pages;
    }
    private static boolean finitePositive(float value) { return value>0 && !Float.isInfinite(value) && !Float.isNaN(value); }
    private static IllegalArgumentException invalidArea() { return new IllegalArgumentException("용지의 인쇄 영역이 너무 작습니다. A4와 여백 설정을 확인해 주세요."); }
}
