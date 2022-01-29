package ch.wsb.svmenuparser.menu;

public enum MenuLabel {
    VEGETARIAN("vegetarian"),
    VEGAN("vegan"),
    ONECLIMATE("oneclimate");

    private final String path;

    MenuLabel(final String path) {
        this.path = path;
    }

    /**
     * Path to the MenuLabel icon
     *
     * @return string path
     */

    @Override
    public String toString() {
        return this.path;
    }
}
