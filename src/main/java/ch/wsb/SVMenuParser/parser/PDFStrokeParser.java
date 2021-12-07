package ch.wsb.SVMenuParser.parser;

import lombok.Getter;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;

import java.awt.*;
import java.awt.geom.GeneralPath;
import java.awt.geom.Point2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class PDFStrokeParser extends PDFGraphicsStreamEngine {
    private final GeneralPath path = new GeneralPath();
    private int clipWindingRule = -1;

    @Getter
    private final List<Rectangle> rectangles = new ArrayList<>();

    /**
     * Create a new PDFStrokeParser instance to extract all rectangles from a PDFPage
     *
     * @param page source pdf page
     */
    public PDFStrokeParser(PDPage page) throws IOException {
        super(page);
        this.processPage(page);
    }

    @Override
    public void appendRectangle(Point2D point1, Point2D point2, Point2D point3, Point2D point4) {
        this.path.moveTo(point1.getX(), point1.getY());
        this.path.lineTo(point2.getX(), point2.getY());
        this.path.lineTo(point3.getX(), point3.getY());
        this.path.lineTo(point4.getX(), point4.getY());
        this.path.closePath();
    }

    @Override
    public void drawImage(PDImage pdImage) {

    }

    @Override
    public void clip(int windingRule) {
        this.clipWindingRule = windingRule;
    }

    @Override
    public void moveTo(float x, float y) {
        this.path.moveTo(x, y);
    }

    @Override
    public void lineTo(float x, float y) {
        this.path.lineTo(x, y);
    }

    @Override
    public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
        this.path.curveTo(x1, y1, x2, y2, x3, y3);
    }

    @Override
    public Point2D getCurrentPoint() {
        return this.path.getCurrentPoint();
    }

    @Override
    public void closePath() {
        this.path.closePath();
    }

    @Override
    public void endPath() {
        if (this.clipWindingRule != -1) {
            this.path.setWindingRule(this.clipWindingRule);
            getGraphicsState().intersectClippingPath(this.path);
            this.clipWindingRule = -1;
        }
        this.path.reset();
    }

    @Override
    public void strokePath() {
        this.rectangles.add(this.path.getBounds());
        this.path.reset();
    }

    @Override
    public void fillPath(int windingRule) {
        this.path.reset();
    }

    @Override
    public void fillAndStrokePath(int windingRule) {
        this.path.reset();
    }

    @Override
    public void shadingFill(COSName shadingName) {
    }
}
