package ch.wsb.SVMenuParser.parser;

import ch.wsb.SVMenuParser.Main;
import ch.wsb.SVMenuParser.fetcher.MenuFetcher;
import ch.wsb.SVMenuParser.menu.Menu;
import ch.wsb.SVMenuParser.menu.MenuLabel;
import ch.wsb.SVMenuParser.menu.MenuPrice;
import ch.wsb.SVMenuParser.menu.MenuWeek;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.ITessAPI;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.Word;
import net.sourceforge.tess4j.util.ImageHelper;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.PDFTextStripperByArea;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class MenuParser {
    public static final int BORDER_WIDTH = 3;
    public static final Color BOUNDS_COLOR = new Color(0x21F6F6);

    @Getter
    private final BufferedImage image;

    @Getter
    private final PDDocument PDF;

    @Getter
    private final MenuWeek menuWeek;

    private final List<Rectangle> bounds = new ArrayList<>();
    private final List<Rectangle> horizontals = new ArrayList<>();
    private final List<Rectangle> verticals = new ArrayList<>();
    private final List<Rectangle> menus = new ArrayList<>();
    private final List<Menu> parsedMenus = new ArrayList<>();
    private Date weekDate;

    public MenuParser(MenuFetcher fetcher) throws URISyntaxException, IOException, ParseException, ExecutionException, InterruptedException {
        Logger.getRootLogger().setLevel(Level.OFF);
        this.image = fetcher.getImage();
        this.PDF = fetcher.getPDF();

        long start = new Date().getTime();

        log.info("Initialized new MenuParser");

        this.getLines();
        log.debug("Successfully calculated table border lines");
        this.calculateMenuBounds();
        log.debug("Successfully calculated menu bounding boxes");

        PDFTextStripper textStripper = new PDFTextStripper();
        String headerText = textStripper.getText(this.PDF).split("\n")[0];
        log.debug("Successfully extracted text from the pdf");

        //check if the pdf can be fully parsed with pdfbox text stripper
        if (StandardCharsets.ISO_8859_1.newEncoder().canEncode(headerText)) {
            //parse header to menu week
            Pattern weekDatePattern = Pattern.compile("[0-9]{1,2}.[0-9]{1,2}. - [0-9]{1,2}.[0-9]{1,2}.[0-9]{4}$");
            Matcher headerMatcher = weekDatePattern.matcher(headerText);
            MatchResult headerResult = headerMatcher.results().toList().get(0);
            this.weekDate = new SimpleDateFormat("dd-MM-yyyy").parse(headerResult.group().split("- ")[1].replace(".", "-"));
            log.debug("Successfully extracted menu week date from pdf text");

            this.readMenus();
        } else this.ocrMenus();

        //create menu week
        this.menuWeek = new MenuWeek(this.parsedMenus);

        long end = new Date().getTime();
        log.info("Successfully parsed {} menus in {}ms", this.menuWeek.getMenus().size(), end - start);
    }

    /**
     * Method to sort out the horizontal and vertical table lines
     */

    private void getLines() throws IOException {
        List<Rectangle> rectangles = new ArrayList<>();
        List<Integer> xCoordinates = new ArrayList<>();
        List<Integer> yCoordinates = new ArrayList<>();
        PDFStrokeParser strokeParser = new PDFStrokeParser(this.PDF.getPage(0));

        int smallestX = -1;
        int smallestY = -1;
        int biggestX = -1;
        int biggestY = -1;

        for (Rectangle boundingBox : strokeParser.getRectangles()) {
            int rectX = boundingBox.x;
            int rectHeight = boundingBox.height;
            int rectY = image.getHeight() / MenuFetcher.IMAGE_SCALE_FACTOR - boundingBox.y - rectHeight;
            int rectWidth = boundingBox.width;
            Rectangle rotatedRectangle = new Rectangle(rectX, rectY, rectWidth, rectHeight);
            rectangles.add(rotatedRectangle);

            if (smallestX == -1 || rotatedRectangle.x < smallestX) smallestX = rotatedRectangle.x;
            if (biggestX == -1 || rotatedRectangle.x + rotatedRectangle.width > biggestX)
                biggestX = rotatedRectangle.x + rotatedRectangle.width;
            if (smallestY == -1 || rotatedRectangle.y < smallestY) smallestY = rotatedRectangle.y;
            if (biggestY == -1 || rotatedRectangle.y + rotatedRectangle.height > biggestY)
                biggestY = rotatedRectangle.y + rotatedRectangle.height;
        }

        Rectangle xLine = new Rectangle(smallestX, smallestY, biggestX - smallestX, 1);
        Rectangle yLine = new Rectangle(smallestX, smallestY, 1, biggestY - smallestY);

        for (Rectangle boundingBox : rectangles) {
            if (boundingBox.intersects(xLine) && !xCoordinates.contains(boundingBox.x)) xCoordinates.add(boundingBox.x);
            if (boundingBox.intersects(yLine) && !yCoordinates.contains(boundingBox.y)) yCoordinates.add(boundingBox.y);
        }

        for (int x : xCoordinates) this.verticals.add(new Rectangle(x, smallestY, 1, biggestY - smallestY));
        for (int y : yCoordinates) this.horizontals.add(new Rectangle(smallestX, y, biggestX - smallestX, 1));

        Rectangle right = new Rectangle(); //add custom right column as vertical line
        Rectangle anyVertical = this.verticals.get(0);
        Rectangle anyHorizontal = this.horizontals.get(0);
        right.setBounds(anyHorizontal.x + anyHorizontal.width, anyVertical.y, anyVertical.width, anyVertical.height);
        this.verticals.add(right);

        //sort the lines in ascending order
        this.horizontals.sort(Comparator.comparingInt(o -> o.y));
        this.verticals.sort(Comparator.comparingInt(o -> o.x));
    }

    /**
     * Method to calculate the menu bounding boxes using the intersection points of the table lines
     */

    private void calculateMenuBounds() {
        int[][] xPoints = new int[this.horizontals.size() - 1][2]; //points lying on the x-axis
        int[][] yPoints = new int[this.verticals.size() - 1][2]; //points lying on the y-axis

        //calculate all x-points for the menu bounds
        for (int i = 0; i < this.horizontals.size() - 1; i++) {
            Rectangle current = this.horizontals.get(i);
            Rectangle next = this.horizontals.get(i + 1);
            xPoints[i] = new int[]{(int) (current.y + current.height / 2f), (int) (next.y + next.height / 2f)};
        }

        //calculate all y-points for the menu bounds
        for (int i = 0; i < this.verticals.size() - 1; i++) {
            Rectangle current = this.verticals.get(i);
            Rectangle next = this.verticals.get(i + 1);
            yPoints[i] = new int[]{(int) (current.x + current.width / 2f), (int) (next.x + next.width / 2f)};
        }

        //add all menu bounds to an array
        for (int[] x : xPoints) {
            for (int[] y : yPoints) {
                Rectangle menu = new Rectangle();
                menu.setBounds(y[0], x[0], y[1] - y[0], x[1] - x[0]);
                this.menus.add(this.upscaleRectangle(menu));
            }
        }
    }

    /**
     * Method to ocr the menus of a week multithreaded
     *
     * @throws ExecutionException   exception thrown when the result of a thread resulted in an exception
     * @throws InterruptedException exception thrown if a running thread gets interrupted
     */

    private void ocrMenus() throws ExecutionException, InterruptedException, IOException, URISyntaxException, ParseException {
        //extract menu week date
        List<Word> paragraphs = this.createTesseractInstance().getWords(this.image, ITessAPI.TessPageIteratorLevel.RIL_PARA);
        Word headerWord = null;

        for (Word paragraph : paragraphs) {
            Rectangle boundingBox = paragraph.getBoundingBox();
            this.bounds.add(boundingBox);
            if (headerWord == null) headerWord = paragraph;
            else if (paragraph.getBoundingBox().y < headerWord.getBoundingBox().y) headerWord = paragraph;
        }

        assert headerWord != null;

        Pattern weekDatePattern = Pattern.compile("[0-9]{1,2}.[0-9]{1,2}. - [0-9]{1,2}.[0-9]{1,2}.[0-9]{4}$");
        Matcher headerMatcher = weekDatePattern.matcher(headerWord.getText().replace("\n", ""));
        MatchResult headerResult = headerMatcher.results().toList().get(0);
        this.weekDate = new SimpleDateFormat("dd-MM-yyyy").parse(headerResult.group().split("- ")[1].replace(".", "-"));

        log.debug("Successfully extracted menu week date from pdf text gained through ocr");
        log.info("Started parsing menus using ocr");

        List<Map.Entry<Rectangle, String[]>> menus = new ArrayList<>();
        List<CompletableFuture<Map.Entry<Rectangle, String[]>>> futures = new ArrayList<>();


        for (Rectangle boundingBox : this.menus) {
            CompletableFuture<Map.Entry<Rectangle, String[]>> future = new CompletableFuture();
            futures.add(future);
            Thread thread = new Thread(() -> {
                try {
                    future.complete(Map.entry(boundingBox, ocrMenu(boundingBox)));
                } catch (IOException | URISyntaxException e) {
                    System.err.println("Failed to ocr menu!");
                    e.printStackTrace();
                }
            }, "MenuOCR-" + this.menus.indexOf(boundingBox));
            thread.start();
        }

        for (CompletableFuture<Map.Entry<Rectangle, String[]>> future : futures) {
            menus.add(future.get());
        }

        for (Map.Entry<Rectangle, String[]> menu : menus) {
            this.parseMenu(menu.getValue()[0], menu.getValue()[1], menu.getKey());
        }
    }

    /**
     * Method to ocr a menu to get its content
     *
     * @param boundingBox bounding box of the menu
     * @return content of the menu
     * @throws IOException        exception thrown if the pdf page to extract text is not found
     * @throws URISyntaxException exception thrown if no tessdata was found
     */

    private String[] ocrMenu(Rectangle boundingBox) throws IOException, URISyntaxException {
        BufferedImage copiedImage = this.getImageCopy();
        Graphics2D graphics = (Graphics2D) copiedImage.getGraphics();
        graphics.setColor(Color.BLUE);
        graphics.setStroke(new BasicStroke(3));

        BufferedImage menuImage = this.image.getSubimage(boundingBox.x + 3, boundingBox.y + 3, boundingBox.width - 6, boundingBox.height - 6);
        int scaledWidth = menuImage.getWidth() * MenuFetcher.IMAGE_SCALE_FACTOR;
        int scaledHeight = menuImage.getHeight() * MenuFetcher.IMAGE_SCALE_FACTOR;
        List<Word> words = this.createTesseractInstance().getWords(ImageHelper.getScaledInstance(menuImage, scaledWidth, scaledHeight), ITessAPI.TessPageIteratorLevel.RIL_PARA);

        Word titleWord = null;

        for (Word word : words) {
            if (titleWord == null) titleWord = word;
            else if (word.getBoundingBox().y < titleWord.getBoundingBox().y) titleWord = word;
        }

        assert titleWord != null;

        int contentY = boundingBox.y + (titleWord.getBoundingBox().y + titleWord.getBoundingBox().height + 15) / MenuFetcher.IMAGE_SCALE_FACTOR;
        int contentHeight = boundingBox.height - (titleWord.getBoundingBox().height + titleWord.getBoundingBox().y + 15) / MenuFetcher.IMAGE_SCALE_FACTOR;

        Rectangle contentBounds = new Rectangle(boundingBox.x, contentY, boundingBox.width, contentHeight);
        Rectangle scaled = this.downscaleRectangle(contentBounds);

        PDFTextStripperByArea areaStripper = new PDFTextStripperByArea();
        areaStripper.addRegion("menu", scaled);
        areaStripper.extractRegions(this.PDF.getPage(0));

        String menuTitle = titleWord.getText().replace("-\n", " ").replace("\n", " ").replace("\r", "");
        String menuContent = areaStripper.getTextForRegion("menu").replace("\u2014", "").replace("-\n", " ").replace("\n", " ").replace("\r", "");

        return new String[]{menuTitle, menuContent};
    }

    /**
     * Method to read the menus exclusively with the pdf file and without ocr
     *
     * @throws IOException      exception thrown if the pdf page to extract text is not found
     * @throws RuntimeException exception thrown if an invalid text was given as input
     */

    private void readMenus() throws IOException, RuntimeException {
        log.info("Started parsing menus without ocr");
        PDFTextStripperByArea areaStripper = new PDFTextStripperByArea();
        //add all text regions to the text stripper
        for (Rectangle boundingBox : this.menus) {
            Rectangle scaled = this.downscaleRectangle(boundingBox);
            areaStripper.addRegion(String.valueOf(this.menus.indexOf(boundingBox)), scaled);
        }
        areaStripper.extractRegions(this.PDF.getPage(0));

        for (String region : areaStripper.getRegions()) {
            String regionText = areaStripper.getTextForRegion(region);
            String[] split = regionText.split("\u2014");
            if (split.length != 2) throw new RuntimeException("No or more than one text separator found");
            String title = split[0].replace("-\n", " ").replace("\n", " ").replace("\r", "");
            String content = split[1].replace("-\n", " ").replace("\n", " ").replace("\r", "");
            parseMenu(title, content, this.menus.get(Integer.parseInt(region)));
        }
    }

    /**
     * Method to extract the menu data from the text
     *
     * @param title       title text
     * @param content     content text
     * @param boundingBox bounding box of the menu
     * @throws IOException exception thrown if an error occurs while extracting the label
     */

    private void parseMenu(String title, String content, Rectangle boundingBox) throws IOException {
        String description = this.getMenuDescription(content);
        MenuLabel label = this.getMenuLabel(boundingBox);
        List<MenuPrice> prices = this.getMenuPrices(content);
        int menuGroup = this.getMenuGroup(boundingBox);
        Date date = this.getMenuDate(boundingBox);
        Menu menu = new Menu(title, prices, description, date, menuGroup, label);
        this.parsedMenus.add(menu);
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
        imageParser.processPage(this.PDF.getPage(0));

        MenuLabel label = null;
        double highestSimilarity = 0d;
        BufferedImage highestSimilarityImage = null;

        for (Rectangle imageBounds : imageParser.getImages().keySet()) {
            int imageX = imageBounds.x * MenuFetcher.IMAGE_SCALE_FACTOR;
            int imageHeight = imageBounds.height * MenuFetcher.IMAGE_SCALE_FACTOR;
            int imageY = this.image.getHeight() - imageBounds.y * MenuFetcher.IMAGE_SCALE_FACTOR - imageHeight;
            int imageWidth = imageBounds.width * MenuFetcher.IMAGE_SCALE_FACTOR;
            Rectangle scaledImageBounds = new Rectangle(imageX, imageY, imageWidth, imageHeight);

            if (boundingBox.intersects(scaledImageBounds)) {
                for (MenuLabel menuLabel : MenuLabel.values()) {
                    BufferedImage defaultIcon = ImageIO.read(Objects.requireNonNull(Main.class.getClassLoader().getResourceAsStream("icons/" + menuLabel.toString() + ".png")));
                    BufferedImage currentIcon = imageParser.getImages().get(imageBounds);
                    double similarity = this.getSimilarity(defaultIcon, currentIcon);
                    if (highestSimilarity < similarity) {
                        highestSimilarity = similarity;
                        highestSimilarityImage = currentIcon;
                        if (similarity > 80) label = menuLabel;
                    }
                }
            }
        }

        //check if there could be a new icon
        if (highestSimilarity < 80 && highestSimilarity > 0) {
            log.warn("Possible new icon found since only a low similarity was found");
            File unknownIcon = new File("unknown-icon-" + new Date().getTime() + ".png");
            if (!unknownIcon.exists()) {
                if (unknownIcon.mkdirs()) ImageIO.write(highestSimilarityImage, "png", unknownIcon);
            }
        }

        return label;
    }

    /**
     * Method to get the date of a menu
     *
     * @param boundingBox bounding box of the menu
     * @return date when the menu was served
     */

    private Date getMenuDate(Rectangle boundingBox) {
        int index = this.menus.indexOf(boundingBox);
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(weekDate);
        calendar.add(Calendar.DAY_OF_WEEK, -calendar.get(Calendar.DAY_OF_WEEK));

        int row = this.getMenuGroup(boundingBox) + 1;
        if ((index + 1) % (this.verticals.size() - 1) == 0) row--;
        int weekDay = index - (row - 1) * (this.verticals.size() - 1);

        calendar.add(Calendar.DAY_OF_WEEK, weekDay + 2); //+2 because calendar week starts at sunday with index 1
        return calendar.getTime();
    }

    /**
     * Method to get the menu group for a menu
     *
     * @param boundingBox bounding box of the menu
     * @return menu group of the menu
     */

    private int getMenuGroup(Rectangle boundingBox) {
        int index = this.menus.indexOf(boundingBox);
        return (index + 1) / (this.verticals.size() - 1);
    }

    /**
     * Method to calculate the similarity between two BufferedImages
     *
     * @param image1 first image to be compared
     * @param image2 second image to be compared
     * @return similarity between the two images in percent
     * @throws IllegalArgumentException error thrown if the given images do not have the same dimensions
     */

    private double getSimilarity(BufferedImage image1, BufferedImage image2) throws IllegalArgumentException {
        int width = image1.getWidth();
        int height = image1.getHeight();

        BufferedImage scaledImage2 = ImageHelper.getScaledInstance(image2, width, height);

        long difference = 0;
        for (int y = height - 1; y >= 0; y--) {
            for (int x = width - 1; x >= 0; x--) {
                int rgb1 = image1.getRGB(x, y);
                int rgb2 = scaledImage2.getRGB(x, y);
                int r1 = (rgb1 >> 16) & 0xff;
                int g1 = (rgb1 >> 8) & 0xff;
                int b1 = rgb1 & 0xff;
                int r2 = (rgb2 >> 16) & 0xff;
                int g2 = (rgb2 >> 8) & 0xff;
                int b2 = rgb2 & 0xff;
                difference += Math.abs(r1 - r2) + Math.abs(g1 - g2) + Math.abs(b1 - b2);
            }
        }

        long maxDifference = 765L * width * height;

        return 100 - (100.0 * difference / maxDifference);
    }

    /**
     * Get all parsed menus
     *
     * @return List with Menu instances
     */

    public List<Menu> getMenus() {
        return this.parsedMenus;
    }

    /**
     * Get all bounding boxes of the parsed menus
     *
     * @return List with bounding boxes
     */

    public List<Rectangle> getMenuBounds() {
        return this.menus;
    }

    /**
     * Get a BufferedImage with the drawn bounding boxes of the recognized menus
     *
     * @return BufferedImage with drawn bounding boxes
     */

    public BufferedImage getDrawnMenuBounds() {
        BufferedImage copy = this.getImageCopy();
        Graphics2D graphics = (Graphics2D) copy.getGraphics();
        graphics.setStroke(new BasicStroke(BORDER_WIDTH));
        graphics.setColor(BOUNDS_COLOR);
        graphics.setFont(new Font(graphics.getFont().getFontName(), Font.PLAIN, 25));

        for (Rectangle menu : this.menus) {
            graphics.drawRect(menu.x, menu.y, menu.width, menu.height);
            graphics.drawString("Menu " + this.menus.indexOf(menu), menu.x + BORDER_WIDTH / 2f, menu.y + menu.height - 2);
        }

        return copy;
    }

    /**
     * Get a BufferedImage with the drawn bounding boxes of the recognized text
     *
     * @return BufferedImage with the drawn text bounding boxes
     */

    public BufferedImage getDrawnBounds() {
        BufferedImage copy = this.getImageCopy();
        Graphics2D graphics = (Graphics2D) copy.getGraphics();
        graphics.setStroke(new BasicStroke(BORDER_WIDTH));
        graphics.setColor(BOUNDS_COLOR);
        graphics.setFont(new Font(graphics.getFont().getFontName(), Font.PLAIN, 30));

        for (Rectangle boundingBox : this.bounds) {
            graphics.drawRect(boundingBox.x, boundingBox.y, boundingBox.width, boundingBox.height);
        }

        return copy;
    }

    /**
     * Internal method to create a copy of the initial image
     *
     * @return copy of the initial BufferedImage
     */

    private BufferedImage getImageCopy() {
        BufferedImage copy = new BufferedImage(this.image.getWidth(), this.image.getHeight(), this.image.getType());
        Graphics2D graphics = (Graphics2D) copy.getGraphics();
        graphics.drawImage(this.image, 0, 0, null);
        return copy;
    }

    /**
     * Internal method to create a new tesseract instance
     *
     * @return new tesseract instance
     * @throws URISyntaxException exception thrown if no tessdata was found
     */

    private Tesseract createTesseractInstance() throws URISyntaxException {
        Tesseract tesseract = new Tesseract();
        tesseract.setLanguage("Latin");
        tesseract.setOcrEngineMode(ITessAPI.TessOcrEngineMode.OEM_LSTM_ONLY);
        tesseract.setDatapath(Paths.get(ClassLoader.getSystemResource("tessdata").toURI()).toString());
        tesseract.setTessVariable("user_defined_dpi", "300");
        tesseract.setTessVariable("preserve_interword_spaces", "1");
        tesseract.setPageSegMode(ITessAPI.TessPageSegMode.PSM_AUTO_OSD);
        tesseract.setTessVariable("debug_file", "/dev/null"); //disable tesseract warnings
        return tesseract;
    }

    /**
     * Internal method to upscale a rectangle by the image scale factor
     *
     * @param rectangle rectangle to be upscaled
     * @return upscaled rectangle
     */

    private Rectangle upscaleRectangle(Rectangle rectangle) {
        int rectX = rectangle.x * MenuFetcher.IMAGE_SCALE_FACTOR;
        int rectY = rectangle.y * MenuFetcher.IMAGE_SCALE_FACTOR;
        int rectWidth = rectangle.width * MenuFetcher.IMAGE_SCALE_FACTOR;
        int rectHeight = rectangle.height * MenuFetcher.IMAGE_SCALE_FACTOR;
        return new Rectangle(rectX, rectY, rectWidth, rectHeight);
    }

    /**
     * Internal method to downscale a rectangle by the image scale factor
     *
     * @param rectangle rectangle to be downscaled
     * @return downscaled rectangle
     */

    private Rectangle downscaleRectangle(Rectangle rectangle) {
        int rectX = rectangle.x / MenuFetcher.IMAGE_SCALE_FACTOR;
        int rectY = rectangle.y / MenuFetcher.IMAGE_SCALE_FACTOR;
        int rectWidth = rectangle.width / MenuFetcher.IMAGE_SCALE_FACTOR;
        int rectHeight = rectangle.height / MenuFetcher.IMAGE_SCALE_FACTOR;
        return new Rectangle(rectX, rectY, rectWidth, rectHeight);
    }
}
