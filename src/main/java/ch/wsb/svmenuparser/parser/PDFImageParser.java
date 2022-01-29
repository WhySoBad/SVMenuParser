package ch.wsb.svmenuparser.parser;

import lombok.Getter;
import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.DrawObject;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.state.*;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.util.Matrix;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PDFImageParser extends PDFStreamEngine {
    @Getter
    Map<Rectangle, BufferedImage> images = new HashMap<>();

    public PDFImageParser() {
        addOperator(new Concatenate());
        addOperator(new DrawObject());
        addOperator(new SetGraphicsStateParameters());
        addOperator(new Save());
        addOperator(new Restore());
        addOperator(new SetMatrix());
    }

    @Override
    protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
        String operation = operator.getName();
        if ("Do".equals(operation)) {
            COSName objectName = (COSName) operands.get(0);
            PDXObject object = getResources().getXObject(objectName);

            if (object instanceof PDImageXObject image) {
                Matrix matrix = getGraphicsState().getCurrentTransformationMatrix();
                Rectangle position = new Rectangle();
                position.setBounds((int) matrix.getTranslateX(), (int) matrix.getTranslateY(), (int) matrix.getScalingFactorX(), (int) matrix.getScalingFactorY());
                BufferedImage bufferedImage = image.getImage();
                BufferedImage copyImage = new BufferedImage(bufferedImage.getWidth(), bufferedImage.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = (Graphics2D) copyImage.getGraphics();

                //remove png transparency of found icons (if they are transparent)
                graphics.setColor(Color.BLACK);
                graphics.drawRect(0, 0, copyImage.getWidth(), copyImage.getHeight());
                graphics.drawImage(bufferedImage, 0, 0, copyImage.getWidth(), copyImage.getHeight(), null);
                graphics.dispose();
                this.images.put(position, copyImage);

            } else if (object instanceof PDFormXObject form) showForm(form);
        } else super.processOperator(operator, operands);
    }
}
