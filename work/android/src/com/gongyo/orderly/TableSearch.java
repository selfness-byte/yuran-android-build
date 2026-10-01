package com.gongyo.orderly;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.text.Normalizer;

/** Pure filtering rules for the current table and its change receipts. */
public final class TableSearch {
    private TableSearch() {}

    public static List<Inventory.Row> filterRows(List<Inventory.Row> rows, String skuQuery, boolean lowStockOnly) {
        List<Inventory.Row> result = new ArrayList<>();
        if (rows == null) return result;
        String query = normalized(skuQuery);
        for (Inventory.Row row : rows) {
            if (row == null || !contains(row.sku, query)) continue;
            if (!lowStockOnly || hasLowStockQuantity(row)) result.add(row);
        }
        return result;
    }

    public static boolean matchesChange(ChangeHistory.Entry entry, String query) {
        if (entry == null) return false;
        String normalizedQuery = searchText(query).trim();
        if (normalizedQuery.isEmpty()) return true;
        String haystack = compact(searchText(entry.action) + " " + searchText(entry.jobTitle)
                + " " + searchText(entry.detail));
        if (haystack.contains(compact(normalizedQuery))) return true;
        for (String keyword : normalizedQuery.split("[\\s\\p{Z}]+")) {
            if (!keyword.isEmpty() && !haystack.contains(compact(keyword))) return false;
        }
        return true;
    }

    private static String searchText(String value) {
        return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private static String compact(String value) { return value.replaceAll("[\\s\\p{Z}]+", ""); }

    private static boolean hasLowStockQuantity(Inventory.Row row) {
        if (row.quantities == null) return false;
        for (long quantity : row.quantities) if (quantity >= 1 && quantity <= 5) return true;
        return false;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean contains(String value, String normalizedQuery) {
        return normalizedQuery.isEmpty() || (value != null && value.toLowerCase(Locale.ROOT).contains(normalizedQuery));
    }
}
