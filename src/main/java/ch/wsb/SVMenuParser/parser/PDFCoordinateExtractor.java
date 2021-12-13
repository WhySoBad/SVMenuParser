package ch.wsb.SVMenuParser.parser;

import lombok.Getter;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripperByArea;
import org.apache.pdfbox.text.TextPosition;

import java.awt.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class PDFCoordinateExtractor extends PDFTextStripperByArea {
    @Getter
    private final List<Map.Entry<String, Rectangle>> words = new ArrayList();

    @Getter
    private final Rectangle region;

    /**
     * Instantiate a new PDFTextStripper object.
     *
     * @param region  region where the text coordinates should be extracted
     * @param pdfPage pdf page of which the text should be extracted
     * @throws IOException If there is an error loading the properties.
     */
    public PDFCoordinateExtractor(Rectangle region, PDPage pdfPage) throws IOException {
        this.region = region;
        this.addRegion("region", region);
        this.extractRegions(pdfPage);
    }

    @Override
    protected void writeString(String text, List<TextPosition> textPositions) {
        Rectangle boundingBox = null;
        for (TextPosition position : textPositions) {
            int x = (int) position.getX();
            int y = (int) position.getY();
            int height = (int) position.getHeight();
            int width = (int) position.getWidth();
            Rectangle bounds = new Rectangle(x, y, width, height);
            if (boundingBox == null) boundingBox = bounds;
            else boundingBox.add(bounds);
        }
        words.add(new AbstractMap.SimpleEntry<>(text, boundingBox));
    }

    /**
     * Method to get all words in the region which can't be parsed by the standard charset
     *
     * @return list with all unparsable words
     */

    public List<Map.Entry<String, Rectangle>> getUnparsableWords() {
        List<Map.Entry<String, Rectangle>> unparsable = new ArrayList<>();
        for (Map.Entry<String, Rectangle> word : this.words) {
            if (!StandardCharsets.ISO_8859_1.newEncoder().canEncode(word.getKey())) unparsable.add(word);
        }
        return unparsable;
    }
}
