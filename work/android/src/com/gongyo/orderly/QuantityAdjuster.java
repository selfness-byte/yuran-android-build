package com.gongyo.orderly;

import com.gongyo.photo.PhotoContract;

import java.util.Map;

/** Applies one deliberate adjustment after all input, match, and quantity checks succeed. */
public final class QuantityAdjuster {
    private QuantityAdjuster() {}
    public static final class Result {
        public final String message;
        public final long previous, current;
        public final boolean rowRemoved, created;
        /** The index before removal, or the current index for a retained/created row. */
        public final int rowIndex;
        public final Inventory.Row row;

        private Result(Inventory.Row row, int rowIndex, int sizeIndex, long previous, long current,
                boolean rowRemoved, boolean created) {
            this.row = row; this.rowIndex = rowIndex; this.previous = previous; this.current = current;
            this.rowRemoved = rowRemoved; this.created = created;
            this.message = row.sku + " " + row.color + " " + Inventory.SIZES[sizeIndex] + " "
                    + previous + " → " + current
                    + (rowRemoved ? " · 모든 수량이 0이 되어 행을 삭제했습니다." : created ? " · 새 행을 추가했습니다." : " · 반영했습니다.");
        }
    }

    /** SKU-specific color rules have the same priority as the order parser. */
    public static Result adjust(Inventory.Job job, String sku, String colorInput, int sizeIndex,
            long quantity, boolean subtract, Map<String, String> globals,
            Map<String, Map<String, String>> scoped) {
        if (job == null || job.rows == null) throw new IllegalArgumentException("조정할 주문이 없습니다.");
        String selectedSku = PhotoContract.key(required(sku, 80, "품번"));
        String enteredColor = required(colorInput, 60, "색상");
        if (sizeIndex < 0 || sizeIndex >= Inventory.SIZES.length)
            throw new IllegalArgumentException("사이즈는 S, M, L, XL 중에서 선택해 주세요.");
        if (quantity < 1 || quantity > Inventory.MAX_QUANTITY)
            throw new IllegalArgumentException("더하거나 뺄 수량은 1~999,999의 정수여야 합니다.");

        String mappedColor = Parser.mappedColor(selectedSku, enteredColor, globals, scoped);
        int matchedIndex = -1;
        for (int i = 0; i < job.rows.size(); i++) {
            Inventory.Row row = job.rows.get(i);
            if (row == null) throw new IllegalArgumentException("내용이 없는 행이 있습니다. 주문 행을 확인해 주세요.");
            if (!PhotoContract.sameKey(selectedSku,row.sku)) continue;
            boolean matches = sameCode(enteredColor, trim(row.sourceColor))
                    || sameCode(enteredColor, trim(row.color))
                    || sameCode(mappedColor, trim(row.color));
            if (!matches) continue;
            if (matchedIndex >= 0)
                throw new IllegalArgumentException("같은 품번·색상에 해당하는 행이 여러 개입니다. 표에서 원하는 행을 눌러 직접 수정해 주세요.");
            matchedIndex = i;
        }

        if(matchedIndex<0&&!subtract&&!Parser.MISSING_COLOR.equals(enteredColor)){
            for(int i=0;i<job.rows.size();i++){
                Inventory.Row row=job.rows.get(i);
                if(!PhotoContract.sameKey(selectedSku,row.sku)||row.total()!=0
                        ||!Parser.MISSING_COLOR.equals(row.sourceColor)||!Parser.MISSING_COLOR.equals(row.color))continue;
                if(matchedIndex>=0)throw new IllegalArgumentException("같은 품번의 색상 미지정 행이 여러 개입니다. 먼저 색상을 지정해 주세요.");
                matchedIndex=i;
            }
        }

        if (matchedIndex < 0) {
            if (subtract) throw new IllegalArgumentException("일치하는 품번·색상 행이 없어 수량을 뺄 수 없습니다.");
            if (job.rows.size() >= Inventory.MAX_ROWS)
                throw new IllegalArgumentException("한 작업은 2,000행까지 지원합니다. 주문을 나누어 주세요.");
            String finalColor = required(mappedColor, 30, "출력 색상");
            // A typed ASCII custom code follows the row editor's uppercase convention.
            if (finalColor.matches("[A-Za-z][A-Za-z0-9_-]{0,15}")) finalColor = asciiUpper(finalColor);
            Inventory.Row created = new Inventory.Row();
            created.sku = selectedSku; created.sourceColor = enteredColor; created.color = finalColor;
            created.quantities[sizeIndex] = quantity;
            int index = job.rows.size();
            job.rows.add(created);
            return new Result(created, index, sizeIndex, 0, quantity, false, true);
        }

        Inventory.Row target = job.rows.get(matchedIndex);
        if (target.quantities == null || target.quantities.length != Inventory.SIZES.length)
            throw new IllegalArgumentException("행의 사이즈 수량이 올바르지 않습니다. 해당 행을 확인해 주세요.");
        for (long existing : target.quantities) if (existing < 0 || existing > Inventory.MAX_QUANTITY)
            throw new IllegalArgumentException("행의 수량은 0~999,999여야 합니다. 해당 행을 확인해 주세요.");
        long previous = target.quantities[sizeIndex];
        if (subtract && quantity > previous)
            throw new IllegalArgumentException("현재 " + Inventory.SIZES[sizeIndex] + " 수량(" + previous + ")보다 많이 뺄 수 없습니다.");
        if (!subtract && quantity > Inventory.MAX_QUANTITY - previous)
            throw new IllegalArgumentException("조정 후 수량은 사이즈별 999,999를 넘을 수 없습니다.");
        long current = subtract ? previous - quantity : previous + quantity;
        boolean rowRemoved = false;
        if(!subtract&&target.total()==0&&Parser.MISSING_COLOR.equals(target.sourceColor)
                &&Parser.MISSING_COLOR.equals(target.color)&&!Parser.MISSING_COLOR.equals(enteredColor)){
            String finalColor=required(mappedColor,30,"출력 색상");
            if(finalColor.matches("[A-Za-z][A-Za-z0-9_-]{0,15}"))finalColor=PhotoContract.key(finalColor);
            PhotoBindings.identityEdited(target,selectedSku,enteredColor);
            target.sku=selectedSku;target.sourceColor=enteredColor;target.color=finalColor;
        }
        target.quantities[sizeIndex] = current;
        return new Result(target, matchedIndex, sizeIndex, previous, current, rowRemoved, false);
    }

    private static String required(String value, int maximum, String label) {
        String text = trim(value);
        if (text.isEmpty()) throw new IllegalArgumentException(label + "을 입력해 주세요.");
        if (text.length() > maximum) throw new IllegalArgumentException(label + "은 " + maximum + "자까지 입력할 수 있습니다.");
        return text;
    }

    private static String trim(String value) { return value == null ? "" : value.trim(); }

    private static String asciiUpper(String value) {
        StringBuilder upper = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            upper.append(c >= 'a' && c <= 'z' ? (char) (c - 'a' + 'A') : c);
        }
        return upper.toString();
    }

    private static boolean sameCode(String left, String right) {
        return PhotoContract.sameKey(left,right);
    }
}
