package ch.wsb.SVMenuParser.menu;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class MenuWeek {
    @Getter
    private final List<Menu> menus;

    public MenuWeek(List<Menu> menus) throws IllegalArgumentException {
        if (menus.size() == 0) throw new IllegalArgumentException("Menus must at least contain one menu");
        this.menus = menus;
    }

    public List<Menu> getMenusForDay(int weekDay) throws IllegalArgumentException {
        if (weekDay < 1 || weekDay > 7) throw new IllegalArgumentException("Weekday must be a valid day index");
        Calendar calendar = Calendar.getInstance();
        List<Menu> matching = new ArrayList<>();
        for (Menu menu : this.menus) {
            calendar.setTime(menu.getDate());
            if (calendar.get(Calendar.DAY_OF_WEEK) == weekDay) matching.add(menu);
        }

        return matching;
    }
}
