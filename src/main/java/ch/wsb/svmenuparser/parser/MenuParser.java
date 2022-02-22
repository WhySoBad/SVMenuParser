package ch.wsb.svmenuparser.parser;

import ch.wsb.svmenuparser.menu.Menu;
import ch.wsb.svmenuparser.menu.MenuLabel;
import ch.wsb.svmenuparser.menu.MenuPrice;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.PDFTextStripperByArea;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URL;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * This class is used to read menu pdfs so that they can be processed
 *
 * Multiple pdfs can be read with #readPdf(PDDocument)
 *
 * All previously read menus can be fetched with #getMenus()
 */
@Slf4j
public class MenuParser {

    private float labelAccuracy;

    private PDFTextStripper textStripper;
    private UnicodeMapper unicodeMapper;
    private Map<MenuLabel, BufferedImage> knownLabels;
    @Getter
    private List<BufferedImage> unknownLabels;

    private List<Rectangle> horizontalLines;
    private List<Rectangle> verticalLines;
    private List<Rectangle> menuBounds;
    private PDDocument pdf;

    @Getter
    private List<Menu> menus;

    /**
     * Creates a map parser with the default paths and values (reads the resources from the library resource directory)
     *
     * @throws IOException exception thrown when the resources cannot be loaded
     */
    public MenuParser() throws IOException {
        this(MenuParser.class.getResource("/maps/capitals.map"), MenuParser.class.getResource("/icons"), 0.8f);
    }

    /**
     * Creates a menu parser
     *
     * @param mapFile       path to the unicode map
     * @param labelFolder   path to the label reference images
     * @param labelAccuracy required amount of accuracy for two labels to be counted the same (0 - 1)
     * @throws IOException exception thrown from loading
     */
    public MenuParser(URL mapFile, URL labelFolder, float labelAccuracy) throws IOException {

        this.labelAccuracy = labelAccuracy;
        this.textStripper = new PDFTextStripper();
        this.menus = new ArrayList<>();
        this.unknownLabels = new ArrayList<>();

        load(mapFile, labelFolder);
    }

    /**
     * Loads the data needed for menu parsing
     *
     * @param map    path to unicode map file
     * @param labels path to folder containing label reference images
     * @throws IOException error from lading
     */
    private void load(URL map, URL labels) throws IOException {
        // Load map file
        unicodeMapper = new UnicodeMapper();
        unicodeMapper.loadMap(new Scanner(map.openStream()).useDelimiter("\\Z").next());

        // Load known labels
        knownLabels = new HashMap<>();
        String path = labels.toString();
        for (MenuLabel value : MenuLabel.values()) {
            URL label = new URL(path + "/" + value.toString() + ".png");
            knownLabels.put(value, ImageIO.read(label));
        }
    }

    /**
     * Reads a pdf and inserts the read menus into the menu array
     *
     * @param doc document to read
     * @throws Exception when something goes wrong
     */
    public void readPDF(PDDocument doc) throws Exception { // Yes, it indeed throws an exception
        log.info("Parsing new pdf");
        long start = System.currentTimeMillis();
        pdf = doc;

        // Fetch menu alignment
        this.getLines();
        log.debug("Successfully calculated table border lines");
        this.calculateMenuBounds();
        log.debug("Successfully calculated menu bounding boxes");

        // Get basic data
        String headerText = getHeaderText(pdf);
        Date weekDate = extractWeekDateFromHeader(headerText);

        // Get the menus
        List<String> menus = extractMenuTextsRaw();
        for (int i = 0; i < menus.size(); i++) {
            this.menus.add(parseMenu(menus.get(i), menuBounds.get(i), weekDate));
        }

        log.info("Read menu PDF in {}ms", System.currentTimeMillis() - start);
    }

    /**
     * Extracts the raw (un-unicodemapped) texts for each menu
     *
     * @return list of raw texts
     * @throws IOException exception from reading
     */
    private List<String> extractMenuTextsRaw() throws IOException {
        PDFTextStripperByArea areaStripper = new PDFTextStripperByArea();
        //add all text regions to the text stripper
        for (Rectangle boundingBox : this.menuBounds) {
            areaStripper.addRegion(String.valueOf(this.menuBounds.indexOf(boundingBox)), boundingBox);
        }
        areaStripper.extractRegions(pdf.getPage(0));

        List<String> strings = new ArrayList<>();
        for (String region : areaStripper.getRegions()) {
            strings.add(areaStripper.getTextForRegion(region));
        }

        return strings;
    }

    /**
     * Parses one menu into a menu object
     *
     * @param text     text of the menu
     * @param bounds   bounds where the menu is
     * @param weekDate date of the menu
     * @return parsed menu
     * @throws IOException occurs if pdf can't be read
     */
    private Menu parseMenu(String text, Rectangle bounds, Date weekDate) throws IOException {
        String title;
        String body;

        if (text.contains("—")) { // If em dash present, split title from it by it
            String[] split = text.split("—");

            title = split[0].trim();
            body = split[1].trim();

            title = title.replace("\n", " ");
            title = Arrays.stream(title.split(" ")).map(word -> word.length() > 0 ? word.charAt(0) + word.toLowerCase().substring(1) : "").collect(Collectors.joining(" "));

        } else { // else, use the last unicode as a reference
            int last = unicodeMapper.getLastUnicodeOccasion(text);

            title = unicodeMapper.process(text.substring(0, last + 1).trim());
            body = unicodeMapper.process(text.substring(last + 1).trim());

            // Fill last quote if not done
            if (title.chars().filter(ch -> ch == '\"').count() % 2 != 0) {
                title += "\"";
                body = body.substring(1);
            }

            // Fill last weirdo quote if not done
            if (title.chars().filter(ch -> ch == '«' || ch == '»').count() % 2 != 0) {
                title += "»";
                body = body.substring(1);
            }
        }

        title = title.replace("-\n", "").replace("\n", " ").replace("\r", "");
        body = body.replace("-\n", "").replace("\n", " ").replace("\r", "");

        return new Menu(title, getMenuPrices(body), getMenuDescription(body), getMenuDate(bounds, weekDate), getMenuGroup(bounds), getMenuLabel(bounds));
    }

    /**
     * Extracts the week date from the pdf header
     *
     * @param header header text to extract
     * @return date to get
     * @throws ParseException regex exception
     */
    private Date extractWeekDateFromHeader(String header) throws ParseException {
        header = header.replace(" ", "");
        Pattern weekDatePattern = Pattern.compile("[0-9]{1,2}.[0-9]{1,2}.-[0-9]{1,2}.[0-9]{1,2}.[0-9]{4}$");
        Matcher headerMatcher = weekDatePattern.matcher(header);
        MatchResult headerResult = headerMatcher.results().toList().get(0);
        return new SimpleDateFormat("dd-MM-yyyy").parse(headerResult.group().split("-")[1].replace(".", "-"));
    }

    /**
     * Method to sort out the horizontal and vertical table lines
     */
    private void getLines() throws IOException {
        this.horizontalLines = new ArrayList<>();
        this.verticalLines = new ArrayList<>();

        List<Rectangle> rectangles = new ArrayList<>();
        List<Integer> xCoordinates = new ArrayList<>();
        List<Integer> yCoordinates = new ArrayList<>();
        PDFStrokeParser strokeParser = new PDFStrokeParser(pdf.getPage(0)); // Only read first page

        int smallestX = -1;
        int smallestY = -1;
        int biggestX = -1;
        int biggestY = -1;

        for (Rectangle boundingBox : strokeParser.getRectangles()) {
            // Flip rectangle
            boundingBox.y = (int) (pdf.getPage(0).getBBox().getHeight() - boundingBox.y - boundingBox.height); // Flip vertical coordinates

            // Add to found rectangles
            rectangles.add(boundingBox);

            if (smallestX == -1 || boundingBox.x < smallestX) smallestX = boundingBox.x;
            if (biggestX == -1 || boundingBox.x + boundingBox.width > biggestX)
                biggestX = boundingBox.x + boundingBox.width;
            if (smallestY == -1 || boundingBox.y < smallestY) smallestY = boundingBox.y;
            if (biggestY == -1 || boundingBox.y + boundingBox.height > biggestY)
                biggestY = boundingBox.y + boundingBox.height;
        }

        // Combine rects to one origin line
        Rectangle xLine = new Rectangle(smallestX, smallestY, biggestX - smallestX, 1);
        Rectangle yLine = new Rectangle(smallestX, smallestY, 1, biggestY - smallestY);

        // Select rects that originate from one of those lines
        for (Rectangle boundingBox : rectangles) {
            if (boundingBox.intersects(xLine) && !xCoordinates.contains(boundingBox.x)) xCoordinates.add(boundingBox.x);
            if (boundingBox.intersects(yLine) && !yCoordinates.contains(boundingBox.y)) yCoordinates.add(boundingBox.y);
        }

        // Create new lines for them
        for (int x : xCoordinates) this.verticalLines.add(new Rectangle(x, smallestY, 1, biggestY - smallestY));
        for (int y : yCoordinates) this.horizontalLines.add(new Rectangle(smallestX, y, biggestX - smallestX, 1));

        //add custom right column as vertical line
        Rectangle right = new Rectangle();
        Rectangle anyVertical = this.verticalLines.get(0);
        Rectangle anyHorizontal = this.horizontalLines.get(0);
        right.setBounds(anyHorizontal.x + anyHorizontal.width, anyVertical.y, anyVertical.width, anyVertical.height);
        this.verticalLines.add(right);

        //sort the lines in ascending order
        this.horizontalLines.sort(Comparator.comparingInt(o -> o.y));
        this.verticalLines.sort(Comparator.comparingInt(o -> o.x));
    }

    /**
     * Method to calculate the menu bounding boxes using the intersection points of the table lines
     */
    private void calculateMenuBounds() {
        this.menuBounds = new ArrayList<>();

        int[][] xPoints = new int[this.horizontalLines.size() - 1][2]; //points lying on the x-axis
        int[][] yPoints = new int[this.verticalLines.size() - 1][2]; //points lying on the y-axis

        //calculate all x-points for the menu bounds
        for (int i = 0; i < this.horizontalLines.size() - 1; i++) {
            Rectangle current = this.horizontalLines.get(i);
            Rectangle next = this.horizontalLines.get(i + 1);
            xPoints[i] = new int[]{(int) (current.y + current.height / 2f), (int) (next.y + next.height / 2f)};
        }

        //calculate all y-points for the menu bounds
        for (int i = 0; i < this.verticalLines.size() - 1; i++) {
            Rectangle current = this.verticalLines.get(i);
            Rectangle next = this.verticalLines.get(i + 1);
            yPoints[i] = new int[]{(int) (current.x + current.width / 2f), (int) (next.x + next.width / 2f)};
        }

        //add all menu bounds to an array
        for (int[] x : xPoints) {
            for (int[] y : yPoints) {
                Rectangle menu = new Rectangle();
                menu.setBounds(y[0], x[0], y[1] - y[0], x[1] - x[0]);
                this.menuBounds.add(menu);
            }
        }
    }

    /**
     * Return the header text of the pdf
     *
     * @param pdf pdf to get text from
     * @return pdf text
     * @throws IOException text reading failed
     */
    private String getHeaderText(PDDocument pdf) throws IOException {
        return unicodeMapper.process(textStripper.getText(pdf).split("\n")[0]); // Get first line
    }

    /**
     * Method to get the menu description without the price
     *
     * @param content content of the menu
     * @return description of the menu
     */
    private String getMenuDescription(String content) {
        return content.split(String.valueOf(MenuPrice.PRICE_PATTERN))[0];
    }

    /**
     * Method to get the prices for a menu
     *
     * @param content content of the menu
     * @return List with all menu prices
     */
    private List<MenuPrice> getMenuPrices(String content) {
        List<MenuPrice> prices = new ArrayList<>();
        Matcher matcher = MenuPrice.PRICE_PATTERN.matcher(content);

        for (MatchResult result : matcher.results().toList()) {
            prices.add(new MenuPrice(result.group().split(" ")[0], result.group().split(" ")[1].replace(",", ".")));
        }

        return prices;
    }

    /**
     * Method to get the label for a menu if one exists
     *
     * @param boundingBox bounding box of the menu text
     * @return The label of the menu (null if none exists)
     * @throws IOException exception thrown if the images can't be read from the pdf page
     */
    private MenuLabel getMenuLabel(Rectangle boundingBox) throws IOException {
        PDFImageParser imageParser = new PDFImageParser();
        imageParser.processPage(pdf.getPage(0));

        Rectangle highestBounds = null;
        MenuLabel highestLabel = null;
        float highest = -1;

        //compare every image in the pdf with the given label icons
        for (Rectangle imageBounds : imageParser.getImages().keySet()) {
            Rectangle flippedBounds = new Rectangle(imageBounds);
            flippedBounds.y = (int) (pdf.getPage(0).getBBox().getHeight() - imageBounds.y - imageBounds.height); // Flip rectangle

            if (boundingBox.intersects(flippedBounds)) { // Is inside of current menu
                for (MenuLabel menuLabel : knownLabels.keySet()) {
                    float similarity = getSimilarity(knownLabels.get(menuLabel), imageParser.getImages().get(imageBounds));

                    if (highest < similarity) {
                        highest = similarity;
                        highestLabel = menuLabel;
                        highestBounds = imageBounds;
                    }
                }
            }
        }

        //check if there could be a new icon
        if (highest < labelAccuracy && highest != -1) {
            log.warn("Possible new icon found since only a low similarity was found");

            boolean add = true;
            for (BufferedImage unknownLabel : unknownLabels) {
                float similarity = getSimilarity(unknownLabel, imageParser.getImages().get(highestBounds));
                if (similarity > labelAccuracy) {
                    add = false;
                    break;
                }
            }

            if (add) {
                log.warn("Found new unknown icon");
                unknownLabels.add(imageParser.getImages().get(highestBounds));
            }

            highestLabel = null;
        }

        return highestLabel;
    }

    /**
     * Method to get the date of a menu
     *
     * @param boundingBox bounding box of the menu
     * @return date when the menu was served
     */
    private Date getMenuDate(Rectangle boundingBox, Date weekDate) {
        int index = this.menuBounds.indexOf(boundingBox);
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(weekDate);
        calendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY); // Our week begins at monday

        int row = this.getMenuGroup(boundingBox);
        int offset = index - ((this.verticalLines.size() - 1) * row);

        calendar.add(Calendar.DAY_OF_WEEK, offset);
        return calendar.getTime();
    }

    /**
     * Method to get the menu group for a menu
     *
     * @param boundingBox bounding box of the menu
     * @return menu group of the menu
     */
    private int getMenuGroup(Rectangle boundingBox) {
        int index = this.menuBounds.indexOf(boundingBox);
        return (index) / (this.verticalLines.size() - 1); // Because one additional line at end
    }

    /**
     * Method to calculate the similarity between two BufferedImages
     *
     * @param image1 first image to be compared
     * @param image2 second image to be compared
     * @return similarity between the two images (1 = they're the same picture)
     */
    private float getSimilarity(BufferedImage image1, BufferedImage image2) {
        int width = image1.getWidth();
        int height = image1.getHeight();

        if (image1.getWidth() != image2.getWidth() || image1.getHeight() != image2.getHeight()) {
            BufferedImage scaled = new BufferedImage(image1.getWidth(), image1.getHeight(), image1.getType());
            scaled.getGraphics().drawImage(image2, 0, 0, image1.getWidth(), image1.getHeight(), null);
            image2 = scaled;
        }

        long difference = 0;
        //compare every pixel in the two images
        for (int y = height - 1; y >= 0; y--) {
            for (int x = width - 1; x >= 0; x--) {
                // Extract Pixel Color
                int rgb1 = image1.getRGB(x, y);
                int rgb2 = image2.getRGB(x, y);
                // Get individual rgb components
                int r1 = (rgb1 >> 16) & 0xff;
                int g1 = (rgb1 >> 8) & 0xff;
                int b1 = rgb1 & 0xff;
                int r2 = (rgb2 >> 16) & 0xff;
                int g2 = (rgb2 >> 8) & 0xff;
                int b2 = rgb2 & 0xff;
                // Compare
                difference += Math.abs(r1 - r2) + Math.abs(g1 - g2) + Math.abs(b1 - b2);
            }
        }

        long maxDifference = 765L * width * height;

        return 1 - (((float) difference) / maxDifference);
    }
}
