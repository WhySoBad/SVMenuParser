package ch.wsb.svmenuparser.fetcher;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URL;
import java.util.Date;

@Slf4j
public class MenuFetcher {
    public static final int IMAGE_SCALE_FACTOR = 3;

    @Getter
    PDDocument PDF;

    /**
     * Create a new MenuFetcher instance
     *
     * @param url url to the menu week which should be downloaded
     * @throws IOException exception thrown when the download fails
     */

    public MenuFetcher(URL url) throws IOException {
        log.info("Initialized new MenuFetcher");
        long start = new Date().getTime();
        if (!url.toString().contains("sv-restaurant")) throw new Error("URL has to contain 'sv-restaurant'");
        if (!url.toString().endsWith(".pdf")) throw new Error("URL has to link to a PDF document");

        InputStream stream = url.openStream();

        long end = new Date().getTime();
        log.info("Successfully downloaded menu week in {}ms", end - start);

        this.PDF = PDDocument.load(stream);
        log.debug("Successfully loaded pdf document");
    }

    /**
     * Create a new MenuFetcher instance
     *
     * @param document document to be parsed
     */

    public MenuFetcher(PDDocument document) {
        log.info("Initialized new MenuFetcher");
        this.PDF = document;
    }

    /**
     * Create a new MenuFetcher instance
     *
     * @param file file of the document
     * @throws IOException exception thrown when there is a parsing error
     */

    public MenuFetcher(File file) throws IOException {
        log.info("Initialized new MenuFetcher");
        this.PDF = PDDocument.load(file);
    }

    /**
     * Get an image of the downloaded PDF document
     *
     * @return image of the downloaded PDF document
     * @throws IOException exception thrown when the image capturing fails
     */

    public BufferedImage getImage() throws IOException {
        PDFRenderer renderer = new PDFRenderer(this.PDF);
        return renderer.renderImage(0, IMAGE_SCALE_FACTOR, ImageType.RGB);
    }

    /**
     * Close the PDF Document
     *
     * @throws IOException exception thrown when the file close fails
     */

    public void closePDF() throws IOException {
        this.PDF.close();
    }
}
