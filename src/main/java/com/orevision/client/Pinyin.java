package com.orevision.client;

import java.util.ArrayList;
import java.util.List;

/**
 * 把方块中文名转成可搜索的拼音键。
 *
 * <p>每个汉字用 {@link PinyinTable} 里的全部读音展开，非汉字直接跳过。多音字会产生多个变体，
 * 每个变体分别拼上全拼与首字母两种形式，最后用 {@link #SEP} 连成一个字符串，
 * 搜索时直接 {@code contains} 就能同时命中「全拼子串」和「首字母子串」。
 */
final class Pinyin {
    private static final char SEP = '\u0001';
    /** 多音字变体上限，防止长名字组合爆炸。 */
    private static final int MAX_VARIANTS = 8;

    private Pinyin() {
    }

    static String key(String name) {
        List<StringBuilder> full = new ArrayList<>();
        List<StringBuilder> initials = new ArrayList<>();
        full.add(new StringBuilder());
        initials.add(new StringBuilder());

        for (int i = 0; i < name.length(); i++) {
            String[] readings = PinyinTable.of(name.charAt(i));
            if (readings == null || readings.length == 0) {
                continue;
            }
            List<StringBuilder> nextFull = new ArrayList<>();
            List<StringBuilder> nextInitials = new ArrayList<>();
            for (String reading : readings) {
                for (int v = 0; v < full.size() && nextFull.size() < MAX_VARIANTS; v++) {
                    nextFull.add(new StringBuilder(full.get(v)).append(reading));
                    nextInitials.add(new StringBuilder(initials.get(v)).append(reading.charAt(0)));
                }
            }
            full = nextFull;
            initials = nextInitials;
        }

        StringBuilder out = new StringBuilder();
        for (StringBuilder sb : full) {
            out.append(sb).append(SEP);
        }
        for (StringBuilder sb : initials) {
            out.append(sb).append(SEP);
        }
        return out.toString();
    }
}
