package ch.wsb.svmenuparser.menu;

import lombok.Getter;

import java.util.regex.Pattern;

public class MenuPrice {
    public static final Pattern PRICE_PATTERN = Pattern.compile("[A-Z]+ [0-9]{1,2}.[0-9]{1,2}");

    @Getter
    private final String group;

    @Getter
    private final String price;

    /**
     * Create a new MenuPrice instance
     *
     * @param group group for which the price is
     * @param price the price for a given group
     */

    public MenuPrice(String group, String price) {
        this.group = group;
        this.price = price.replace(",", ".");
    }
}
