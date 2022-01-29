package ch.wsb.svmenuparser.menu;

import lombok.Getter;

import java.util.Date;
import java.util.List;

/**
 * This class stores a simple menu
 */
public class Menu {
    @Getter
    private String title;

    @Getter
    private final List<MenuPrice> price;

    @Getter
    private String description;

    @Getter
    private final Date date;

    @Getter
    private final int menuGroup;

    @Getter
    private final MenuLabel label;


    /**
     * Create a new Menu instance
     *
     * @param title       title of the menu
     * @param price       prices of the menu
     * @param description content of the menu
     * @param date        date of the menu
     * @param menuGroup   menu group of the menu
     * @param label       label of the menu
     */
    public Menu(String title, List<MenuPrice> price, String description, Date date, int menuGroup, MenuLabel label) {
        this.price = price;
        this.menuGroup = menuGroup;
        this.date = date;
        this.label = label;
        this.title = title.replace("-\n", " ").replace("\n", " ").replace("\r", "").replace("\t", " ").replace("- ", "-").replace("  ", " ").trim();
        this.description = description.replace("-\n", " ").replace("\n", " ").replace("\r", "").replace("\t", " ").replace("- ", "-").replace("  ", " ").trim();
    }
}
