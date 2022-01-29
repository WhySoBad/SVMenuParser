package ch.wsb.svmenuparser.parser;

import ch.wsb.svmenuparser.fetcher.MenuFetcher;
import ch.wsb.svmenuparser.menu.Menu;
import ch.wsb.svmenuparser.menu.MenuLabel;
import ch.wsb.svmenuparser.menu.MenuPrice;
import ch.wsb.svmenuparser.menu.MenuWeek;
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

    private float labelAccuracy;

    private PDFTextStripper textStripper;
    private UnicodeMapper unicodeMapper;
    private Map<MenuLabel, BufferedImage> knownLabels;
    private List<BufferedImage> unknownLabels;

    private List<Rectangle> horizontalLines;
    private List<Rectangle> verticalLines;
    private List<Rectangle> menuBounds;
    private PDDocument pdf;

    private List<Menu> menus;

    public MenuParser(UnicodeMapper map, float labelAccuracy, MenuFetcher fetcher) throws URISyntaxException, IOException, ParseException, ExecutionException, InterruptedException {

        this.labelAccuracy = labelAccuracy;
        this.textStripper = new PDFTextStripper();

        this.unicodeMapper = map;


        Logger.getRootLogger().setLevel(Level.OFF); // Disable annoying logging things
        this.image = fetcher.getImage();
        this.PDF = fetcher.getPDF();

        long start = new Date().getTime();

        log.info("Initialized new MenuParser");

        this.getLines(this.PDF);
        log.debug("Successfully calculated table border lines");
        this.calculateMenuBounds();
        log.debug("Successfully calculated menu bounding boxes");

        PDFTextStripper textStripper = new PDFTextStripper();
        String headerText = textStripper.getText(this.PDF).split("\n")[0];
        log.debug("Successfully extracted text from the pdf");

        this.readMenus();
        /*
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
        */
        fetcher.closePDF();

        //create menu week

        long end = new Date().getTime();
        log.info("Successfully parsed {} menus in {}ms", this.menuWeek.getMenus().size(), end - start);

    }

    public void readPDF(MenuFetcher fetcher) throws Exception { // Yes, it indeed throws an exception
        log.info("Parsing new pdf");
        long start = System.currentTimeMillis();
        PDDocument pdf = fetcher.getPDF();

        // Fetch menu alignment
        this.getLines(pdf);
        log.debug("Successfully calculated table border lines");
        this.calculateMenuBounds();
        log.debug("Successfully calculated menu bounding boxes");

        // Get basic data
        String headerText = getHeaderText(pdf);
        Date weekDate = extractWeekDateFromHeader(headerText);

        // Get the menus
        List<String> menus = extractMenuTextsRaw(pdf);
        for (String menu : menus) {
            this.menus.add(parseMenu(menu, menuBounds.get(menus.indexOf(menu)), weekDate));
        }

        log.debug("Read menu PDF in {}ms", System.currentTimeMillis() - start);
    }

    private List<String> extractMenuTextsRaw(PDDocument pdf) throws IOException {
        log.info("Started parsing menus without ocr");
        PDFTextStripperByArea areaStripper = new PDFTextStripperByArea();
        //add all text regions to the text stripper
        for (Rectangle boundingBox : this.menuBounds) {
            Rectangle scaled = this.downscaleRectangle(boundingBox);
            areaStripper.addRegion(String.valueOf(this.menuBounds.indexOf(boundingBox)), scaled);
        }
        areaStripper.extractRegions(pdf.getPage(0));

        List<String> strings = new ArrayList<>();
        for (String region : areaStripper.getRegions()) {
            strings.add(areaStripper.getTextForRegion(region));
        }

        return strings;
    }

    private Menu parseMenu(String text, Rectangle bounds, Date weekDate) throws IOException {
        String title;
        String body;

        if (text.contains("—")) { // If em dash present, split title from it by it
            String[] split = text.split("");

            title = split[0].replace("-\n", " ").replace("\n", " ").replace("\r", "");
            body = split[1].replace("-\n", " ").replace("\n", " ").replace("\r", "");
        } else { // else, use the last unicode as a reference
            int last = unicodeMapper.getLastUnicodeOccasion(text);

            title = text.substring(0, last + 1);
            body = text.substring(last);
        }

        title = unicodeMapper.process(title);
        body = unicodeMapper.process(body);

        return new Menu(title, getMenuPrices(body), getMenuDescription(text), getMenuDate(bounds, weekDate), getMenuGroup(bounds), getMenuLabel(bounds));
    }

    public Date extractWeekDateFromHeader(String header) throws ParseException {
        Pattern weekDatePattern = Pattern.compile("[0-9]{1,2}.[0-9]{1,2}. - [0-9]{1,2}.[0-9]{1,2}.[0-9]{4}$");
        Matcher headerMatcher = weekDatePattern.matcher(header);
        MatchResult headerResult = headerMatcher.results().toList().get(0);
        return new SimpleDateFormat("dd-MM-yyyy").parse(headerResult.group().split("- ")[1].replace(".", "-"));
    }

    /**
     * Method to sort out the horizontal and vertical table lines
     */
    private void getLines(PDDocument pdf) throws IOException {
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
                this.menuBounds.add(this.upscaleRectangle(menu));
            }
        }
    }

    private String getHeaderText(PDDocument pdf) throws IOException {
        return unicodeMapper.process(textStripper.getText(pdf).split("\n")[0]); // Get first line
    }

    /**
     * Method to ocr the menus of a week multithreaded
     *
     * @throws ExecutionException   exception thrown when the result of a thread resulted in an exception
     * @throws InterruptedException exception thrown if a running thread gets interrupted
     * @throws IOException
     * @throws URISyntaxException
     * @throws ParseException
     * @throws RuntimeException
     */

    private void ocrMenus() throws ExecutionException, InterruptedException, IOException, URISyntaxException, ParseException, RuntimeException {
        float downscaleFactor = 0.25f;

        //extract menu week date
        int scaledImageWidth = (int) (this.image.getWidth() * downscaleFactor);
        int scaledImageHeight = (int) (this.image.getHeight() * downscaleFactor);
        BufferedImage scaledImage = ImageHelper.getScaledInstance(this.image, scaledImageWidth, scaledImageHeight);
        List<Word> textlines = this.createTesseractInstance().getWords(scaledImage, ITessAPI.TessPageIteratorLevel.RIL_TEXTLINE);
        Word headerWord = null;

        List<Word> headerWords = new ArrayList<>();

        for (Word textline : textlines) {
            Rectangle boundingBox = textline.getBoundingBox();
            if (headerWord == null && boundingBox.width > boundingBox.height * 3) headerWord = textline;
            else if (headerWord != null && boundingBox.y < headerWord.getBoundingBox().y && boundingBox.width > boundingBox.height * 3)
                headerWord = textline;
        }

        assert headerWord != null;

        for (Word textline : textlines) {
            Rectangle highestBounds = headerWord.getBoundingBox();
            Rectangle textlineBounds = textline.getBoundingBox();
            if (textlineBounds.y < highestBounds.y + highestBounds.height && !headerWord.getText().replace("\n", "").equals(""))
                headerWords.add(textline);
        }

        Pattern headerDatePattern = Pattern.compile("\\d{1,2}.\\d{1,2}.\\d{4}");
        String dateText = null;
        long highestCount = 0;

        StringBuilder headerText = new StringBuilder();

        for (Word word : headerWords) {
            String transformedText = word.getText().replace("\n", "").replace(" ", "").replace(",", ".");
            headerText.append(transformedText);
            Matcher matcher = headerDatePattern.matcher(transformedText);
            long count = matcher.results().count();
            if (count > highestCount) highestCount = count;
            if (count == 1) {
                String[] split = transformedText.split(String.valueOf(headerDatePattern));
                for (String part : split) transformedText = transformedText.replace(part, "");
                dateText = transformedText;
            }
        }

        if(highestCount == 0) {
            String transformedHeaderText = headerText.toString();
            Matcher matcher = headerDatePattern.matcher(transformedHeaderText);
            if (matcher.results().count() == 1) {
                String[] split = headerText.toString().split(String.valueOf(headerDatePattern));
                for (String part : split) transformedHeaderText = transformedHeaderText.replace(part, "");
                dateText = transformedHeaderText;
            }
        }

        if (highestCount == 0 && dateText == null) {
            BufferedImage copy = this.getImageCopy();
            Graphics2D graphics = (Graphics2D) copy.getGraphics();
            graphics.setStroke(new BasicStroke(MenuParser.BORDER_WIDTH));
            graphics.setColor(MenuParser.BOUNDS_COLOR);
            for (Word word : headerWords) {
                int scaledWidth = (int) (word.getBoundingBox().width * (1 / downscaleFactor));
                int scaledHeight = (int) (word.getBoundingBox().height * (1 / downscaleFactor));
                int scaledX = (int) (word.getBoundingBox().x * (1 / downscaleFactor));
                int scaledY = (int) (word.getBoundingBox().y * (1 / downscaleFactor));
                graphics.draw(new Rectangle(scaledX, scaledY, scaledWidth, scaledHeight));
            }
            File file = new File("errors/nodate-" + new Date().getTime() + ".png");
            if (!file.exists()) {
                if (file.mkdirs()) ImageIO.write(copy, "png", file);
            }
        }

        if (dateText == null) throw new RuntimeException("No week date text detected in provided pdf document");
        this.weekDate = new SimpleDateFormat("dd.MM.yyyy").parse(dateText);
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
        Rectangle titleBounds = null;

        PDFCoordinateExtractor coordinateExtractor = new PDFCoordinateExtractor(this.downscaleRectangle(boundingBox), this.PDF.getPage(0));
        for (Map.Entry<String, Rectangle> entry : coordinateExtractor.getUnparsableWords()) {
            int entryX = entry.getValue().x * MenuFetcher.IMAGE_SCALE_FACTOR;
            int entryY = (entry.getValue().y - entry.getValue().height) * MenuFetcher.IMAGE_SCALE_FACTOR;
            int entryWidth = entry.getValue().width * MenuFetcher.IMAGE_SCALE_FACTOR;
            int entryHeight = entry.getValue().height * MenuFetcher.IMAGE_SCALE_FACTOR;
            Rectangle entryBounds = new Rectangle(entryX, entryY, entryWidth, entryHeight);
            if (titleBounds == null) titleBounds = entryBounds;
            else titleBounds.add(entryBounds);
        }
        assert titleBounds != null;

        if (titleBounds == null) {
            log.info(String.valueOf(coordinateExtractor.getUnparsableWords().size()));
            BufferedImage copy = this.getImageCopy();
            Graphics2D graphics = (Graphics2D) copy.getGraphics();
            graphics.setStroke(new BasicStroke(MenuParser.BORDER_WIDTH));
            graphics.setColor(MenuParser.BOUNDS_COLOR);
            graphics.draw(boundingBox);
            File file = new File("errors/notitle-" + new Date().getTime() + ".png");
            if(!file.exists()) {
                if(file.mkdirs())ImageIO.write(copy, "png", file);
            }
        }

        BufferedImage menuImage = this.image.getSubimage(titleBounds.x - 10, titleBounds.y - 10, titleBounds.width + 20, titleBounds.height + 20);
        int scaledWidth = menuImage.getWidth() * MenuParser.MENU_SCALE_FACTOR;
        int scaledHeight = menuImage.getHeight() * MenuParser.MENU_SCALE_FACTOR;

        List<Word> textlines = this.createTesseractInstance().getWords(ImageHelper.getScaledInstance(menuImage, scaledWidth, scaledHeight), ITessAPI.TessPageIteratorLevel.RIL_TEXTLINE);
        StringBuilder titleText = new StringBuilder();
        for (Word textline : textlines) {
            String replacedText = textline.getText().replace("\u2014", "").replace("-\n", "").replace("\n", " ").replace("\r", "\n");
            titleText.append(replacedText);
        }

        //calculate content bounding box
        int contentY = boundingBox.y + (titleBounds.height + titleBounds.y - boundingBox.y) + 5;
        int contentHeight = boundingBox.height - (titleBounds.height + titleBounds.y - boundingBox.y) + 5;

        Rectangle contentBounds = new Rectangle(boundingBox.x, contentY, boundingBox.width, contentHeight);
        Rectangle scaled = this.downscaleRectangle(contentBounds);

        PDFTextStripperByArea areaStripper = new PDFTextStripperByArea();
        areaStripper.addRegion("menu", scaled);
        areaStripper.extractRegions(this.PDF.getPage(0));

        String menuTitle = titleText.toString();
        String menuContent = areaStripper.getTextForRegion("menu").replace("\u2014", "").replace("-\n", " ").replace("\n", " ").replace("\r", "");

        //return string array with menu title and menu content
        return new String[]{menuTitle, menuContent};
    }

    /**
     * Method to read the menus exclusively with the pdf file and without ocr
     *
     * @throws IOException      exception thrown if the pdf page to extract text is not found
     * @throws RuntimeException exception thrown if an invalid text was given as input
     */

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
        calendar.add(Calendar.DAY_OF_WEEK, -calendar.get(Calendar.DAY_OF_WEEK));

        int row = this.getMenuGroup(boundingBox) + 1;
        if ((index + 1) % (this.verticalLines.size() - 1) == 0) row--;
        int weekDay = index - (row - 1) * (this.verticalLines.size() - 1);

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
        int index = this.menuBounds.indexOf(boundingBox);
        return (index + 1) / (this.verticalLines.size() - 1);
    }

    /**
     * Method to calculate the similarity between two BufferedImages
     *
     * @param image1 first image to be compared
     * @param image2 second image to be compared
     * @return similarity between the two images (1 == they're the same picture)
     */
    private float getSimilarity(BufferedImage image1, BufferedImage image2) {
        int width = image1.getWidth();
        int height = image1.getHeight();

        if(image1.getWidth() != image2.getWidth() || image1.getHeight() != image2.getHeight()) image2 = ImageHelper.getScaledInstance(image2, image1.getWidth(), image2.getHeight());

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
