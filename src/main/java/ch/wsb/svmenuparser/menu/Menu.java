package ch.wsb.svmenuparser.menu;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Date;
import java.util.List;

/**
 * This class stores a simple menu
 */
@AllArgsConstructor
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
}
