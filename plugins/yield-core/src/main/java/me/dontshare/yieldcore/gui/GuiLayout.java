package me.dontshare.yieldcore.gui;

import java.util.stream.IntStream;

/**
 * Where things go in a chest menu so it reads as centred.
 * <p>
 * The house layout is a one-slot border with content in the seven columns
 * inside it, and every row centred on the middle column - including a last
 * row that is only partly full, which is what used to make list screens
 * look lopsided (three items hugging the left edge of an otherwise empty
 * row). The bottom row is the control bar, centred on its middle slot.
 */
public final class GuiLayout {

    /** Columns inside the border. */
    public static final int INNER_WIDTH = 7;

    private GuiLayout() {
    }

    /** How many items fit in {@code rows} rows of the inner grid. */
    public static int capacity(int rows) {
        return rows * INNER_WIDTH;
    }

    /** The middle slot of {@code row} - where a lone header or close button goes. */
    public static int center(int row) {
        return row * 9 + 4;
    }

    /**
     * {@code count} slots in {@code row}, centred on its middle column. Up
     * to seven stay inside the border; eight or nine use the full row. An
     * even count has no exact middle, so it leaves the middle slot empty
     * and splits evenly either side of it (2 -> columns 3 and 5).
     */
    public static int[] centeredRow(int row, int count) {
        int n = Math.max(0, Math.min(9, count));
        int[] slots = new int[n];
        int base = row * 9;
        if (n >= 8) {
            for (int i = 0; i < n; i++) {
                slots[i] = base + (n == 9 ? i : (i < 4 ? i : i + 1));
            }
            return slots;
        }
        if (n % 2 == 1) {
            int first = 4 - n / 2;
            for (int i = 0; i < n; i++) {
                slots[i] = base + first + i;
            }
        } else {
            int half = n / 2;
            for (int i = 0; i < half; i++) {
                slots[i] = base + 4 - half + i;
                slots[half + i] = base + 5 + i;
            }
        }
        return slots;
    }

    /**
     * {@code count} slots flowing through the inner grid from
     * {@code firstRow} down, seven to a row, each row centred - so a page
     * with 17 items shows two full rows and a centred row of three.
     */
    public static int[] centered(int firstRow, int count) {
        int[] slots = new int[Math.max(0, count)];
        int placed = 0;
        int row = firstRow;
        while (placed < count) {
            int inRow = Math.min(INNER_WIDTH, count - placed);
            int[] rowSlots = centeredRow(row, inRow);
            System.arraycopy(rowSlots, 0, slots, placed, inRow);
            placed += inRow;
            row++;
        }
        return slots;
    }

    /** Every slot of the menu - for filling the background before placing anything. */
    public static IntStream all(int rows) {
        return IntStream.range(0, rows * 9);
    }
}
