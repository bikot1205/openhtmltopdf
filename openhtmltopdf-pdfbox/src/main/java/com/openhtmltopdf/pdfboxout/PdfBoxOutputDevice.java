/*
 * {{{ header & license
 * Copyright (c) 2006 Wisconsin Court System
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation; either version 2.1
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 * }}}
 */
package com.openhtmltopdf.pdfboxout;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.LinearGradientPaint;
import java.awt.Paint;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.RenderingHints.Key;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.PathIterator;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.logging.Level;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontFactory;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitHeightDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.openhtmltopdf.bidi.BidiReorderer;
import com.openhtmltopdf.bidi.SimpleBidiReorderer;
import com.openhtmltopdf.css.parser.FSCMYKColor;
import com.openhtmltopdf.css.parser.FSColor;
import com.openhtmltopdf.css.parser.FSRGBColor;
import com.openhtmltopdf.css.style.CalculatedStyle;
import com.openhtmltopdf.css.style.CssContext;
import com.openhtmltopdf.extend.FSImage;
import com.openhtmltopdf.extend.OutputDevice;
import com.openhtmltopdf.layout.SharedContext;
import com.openhtmltopdf.pdfboxout.PdfBoxFontResolver.FontDescription;
import com.openhtmltopdf.pdfboxout.PdfBoxTextRenderer.ReplacementChar;
import com.openhtmltopdf.render.AbstractOutputDevice;
import com.openhtmltopdf.render.BlockBox;
import com.openhtmltopdf.render.Box;
import com.openhtmltopdf.render.FSFont;
import com.openhtmltopdf.render.InlineLayoutBox;
import com.openhtmltopdf.render.InlineText;
import com.openhtmltopdf.render.JustificationInfo;
import com.openhtmltopdf.render.PageBox;
import com.openhtmltopdf.render.RenderingContext;
import com.openhtmltopdf.util.Configuration;
import com.openhtmltopdf.util.XRLog;

public class PdfBoxOutputDevice extends AbstractOutputDevice implements OutputDevice {
    private static final int FILL = 1;
    private static final int STROKE = 2;
    private static final int CLIP = 3;

    private static final AffineTransform IDENTITY = new AffineTransform();

    private static final BasicStroke STROKE_ONE = new BasicStroke(1);

    private static final boolean ROUND_RECT_DIMENSIONS_DOWN = Configuration.isTrue("xr.pdf.round.rect.dimensions.down", false);

    private PDPage _page;
    private PdfContentStreamAdapter _cp;
    private float _pageHeight;

    private PdfBoxFSFont _font;

    private AffineTransform _transform = new AffineTransform();

    private FSColor _color = FSRGBColor.BLACK;
    private FSColor _fillColor;
    private FSColor _strokeColor;

    private Stroke _stroke = null;
    private Stroke _originalStroke = null;
    private Stroke _oldStroke = null;

    private Area _clip;

    private SharedContext _sharedContext;
    private float _dotsPerPoint;

    private PDDocument _writer;

    private Map _readerCache = new HashMap();

    private PDDestination _defaultDestination;

    private List _bookmarks = new ArrayList();

    private List _metadata = new ArrayList();

    private Box _root;

    private int _startPageNo;

    private int _nextFormFieldIndex;
    
    private final boolean _testMode;
    
    private PdfBoxLinkManager _linkManager;
    
    private RenderingContext _renderingContext;
    
    private BidiReorderer _reorderer = new SimpleBidiReorderer();

    public PdfBoxOutputDevice(float dotsPerPoint, boolean testMode) {
        _dotsPerPoint = dotsPerPoint;
        _testMode = testMode;
    }

    public void setWriter(PDDocument writer) {
        _writer = writer;
    }

    public PDDocument getWriter() {
        return _writer;
    }

    public int getNextFormFieldIndex() {
        return ++_nextFormFieldIndex;
    }

    public void initializePage(PDPageContentStream currentPage, PDPage page, float height) {
        _cp = new PdfContentStreamAdapter(currentPage);
        _page = page;
        _pageHeight = height;

        _cp.saveGraphics();

        _transform = new AffineTransform();
        _transform.scale(1.0d / _dotsPerPoint, 1.0d / _dotsPerPoint);

        _stroke = transformStroke(STROKE_ONE);
        _originalStroke = _stroke;
        _oldStroke = _stroke;

        setStrokeDiff(_stroke, null);

        if (_defaultDestination == null) {
            PDPageFitHeightDestination dest = new PDPageFitHeightDestination();
            dest.setPage(page);
        }
    }

    public void finishPage() {
        _cp.restoreGraphics();
        _cp.closeContent();
    }

    public void paintReplacedElement(RenderingContext c, BlockBox box) {
        PdfBoxReplacedElement element = (PdfBoxReplacedElement) box.getReplacedElement();
        element.paint(c, this, box);
    }

    public void paintBackground(RenderingContext c, Box box) {
        super.paintBackground(c, box);

        _linkManager.processLinkLater(c, box, _page, _pageHeight, _transform);
    }



    public float getDeviceLength(float length) {
        return length / _dotsPerPoint;
    }


    public void drawBorderLine(Shape bounds, int side, int lineWidth, boolean solid) {
        draw(bounds);
    }

    public void setColor(FSColor color) {
        if (color instanceof FSRGBColor) {
            _color = color;
        } else if (color instanceof FSCMYKColor) {
            _color = color;
        } else {
            assert(_color instanceof FSRGBColor || _color instanceof FSCMYKColor);
        }
    }

    public void draw(Shape s) {
        followPath(s, STROKE);
    }

    protected void drawLine(int x1, int y1, int x2, int y2) {
        Line2D line = new Line2D.Double(x1, y1, x2, y2);
        draw(line);
    }

    public void drawRect(int x, int y, int width, int height) {
        draw(new Rectangle(x, y, width, height));
    }

    public void drawOval(int x, int y, int width, int height) {
        Ellipse2D oval = new Ellipse2D.Float(x, y, width, height);
        draw(oval);
    }

    public void fill(Shape s) {
        followPath(s, FILL);
    }

    public void fillRect(int x, int y, int width, int height) {
        if (ROUND_RECT_DIMENSIONS_DOWN) {
            fill(new Rectangle(x, y, width - 1, height - 1));
        } else {
            fill(new Rectangle(x, y, width, height));
        }
    }

    public void fillOval(int x, int y, int width, int height) {
        Ellipse2D oval = new Ellipse2D.Float(x, y, width, height);
        fill(oval);
    }

    public void translate(double tx, double ty) {
        _transform.translate(tx, ty);
    }

    public Object getRenderingHint(Key key) {
        return null;
    }

    public void setRenderingHint(Key key, Object value) {
    }

    public void setFont(FSFont font) {
        _font = ((PdfBoxFSFont) font);
    }

    private AffineTransform normalizeMatrix(AffineTransform current) {
        double[] mx = new double[6];
        AffineTransform result = new AffineTransform();
        result.getMatrix(mx);
        mx[3] = -1;
        mx[5] = _pageHeight;
        result = new AffineTransform(mx);
        result.concatenate(current);
        return result;
    }

    public void drawString(String s, float x, float y, JustificationInfo info) {
        PDFont firstFont = _font.getFontDescription().get(0).getFont();
        
        // First check if the string will print with the current font entirely.
        try {
            firstFont.getStringWidth(s);
            // We got here, so all is good.
            drawStringFast(s, x, y, info, _font.getFontDescription().get(0), _font.getSize2D());
            return;
        } 
        catch (Exception e) {
            // Fallthrough, we'll have to process the string into font runs.
        }
        
        List<FontRun> fontRuns = replaceCharacters(_font, s, _reorderer);
        float xOffset = 0f;
        
        for (FontRun run : fontRuns) {
            drawStringFast(run.str, x + xOffset, y, info, run.des, _font.getSize2D());
            try {
                xOffset += (run.des.getFont().getStringWidth(run.str) / 1000f) * _font.getSize2D();
            } catch (Exception e) {
                XRLog.render(Level.WARNING, "BUG. Font didn't contain expected character.", e);
            }
        }
    }
    
    public void drawStringFast(String s, float x, float y, JustificationInfo info, FontDescription desc, float fontSize) {
        // TODO: Emulate bold and italic.
        
        if (s.length() == 0)
            return;

        ensureFillColor();
        AffineTransform at = (AffineTransform) getTransform().clone();
        at.translate(x, y);
        AffineTransform inverse = normalizeMatrix(at);
        AffineTransform flipper = AffineTransform.getScaleInstance(1, -1);
        inverse.concatenate(flipper);
        inverse.scale(_dotsPerPoint, _dotsPerPoint);
        double[] mx = new double[6];
        inverse.getMatrix(mx);
        
        float b = (float) mx[1];
        float c = (float) mx[2];
        
        fontSize = fontSize / _dotsPerPoint;
        
        _cp.beginText();
        _cp.setFont(desc.getFont(), fontSize);
        _cp.setTextMatrix((float) mx[0], b, c, (float) mx[3], (float) mx[4], (float) mx[5]);
        
        if (info != null) {
            _cp.setTextSpacing(info.getNonSpaceAdjust());
            _cp.setSpaceSpacing(info.getSpaceAdjust());
        }
        
        _cp.drawString(s);
        _cp.endText();
    }
    
    static class FontRun {
        String str;
        FontDescription des;
    }
    
    private List<FontRun> replaceCharacters(FSFont font, String str, BidiReorderer reorderer) {
        StringBuilder sb = new StringBuilder();
        ReplacementChar replace = PdfBoxTextRenderer.getReplacementChar(font);
        List<FontDescription> fonts = ((PdfBoxFSFont) font).getFontDescription();
        List<FontRun> runs = new ArrayList<FontRun>();
        FontRun current = new FontRun();
        
        for (int i = 0; i < str.length(); ) {
            int unicode = str.codePointAt(i);
            i += Character.charCount(unicode);
            String ch = String.valueOf(Character.toChars(unicode));
            boolean gotChar = false;
            
            FONT_LOOP:
            for (FontDescription des : fonts) {
                try {
                    des.getFont().getStringWidth(ch);
                    // We got here, so this font has this character.
                    if (current.des == null) {
                        // First character of run.
                        current.des = des;
                    }
                    else if (des != current.des) {
                        // We have changed font, so we'll start a new font run.
                        current.str = sb.toString();
                        runs.add(current);
                        current = new FontRun();
                        current.des = des;
                        sb = new StringBuilder();
                    }

                    sb.append(ch);
                    gotChar = true;
                    break;
                }
                catch (Exception e1) {
                    if (reorderer.isLiveImplementation()) {
                        // Character is not in font! Next, we try deshaping.
                        String deshaped = reorderer.deshapeText(ch);
                        try {
                            des.getFont().getStringWidth(deshaped);
                            // We got here, so this font has this deshaped character.
                            if (current.des == null) {
                                // First character of run.
                                current.des = des;
                            }
                            else if (des != current.des) {
                                // We have changed font, so we'll start a new font run.
                                current.str = sb.toString();
                                runs.add(current);
                                current = new FontRun();
                                current.des = des;
                                sb = new StringBuilder();
                            }
                            sb.append(deshaped);
                            gotChar = true;
                            break FONT_LOOP;
                        }
                        catch (Exception e2) {
                            // Keep trying with next font.
                        }
                    }
                }
            }
            
            if (!gotChar) {
                // We still don't have the character after all that. So use replacement character.
                if (current.des == null) {
                    // First character of run.
                    current.des = replace.fontDescription;
                }
                else if (replace.fontDescription != current.des) {
                    // We have changed font, so we'll start a new font run.
                    current.str = sb.toString();
                    runs.add(current);
                    current = new FontRun();
                    current.des = replace.fontDescription;
                    sb = new StringBuilder();
                }
                
                if (Character.isSpaceChar(unicode)) {
                    sb.append(' ');
                }
                else {
                    sb.append(replace.replacement);
                }
            }
        }

        if (sb.length() > 0) {
            current.str = sb.toString();
            runs.add(current);
        }
        
        return runs;
    }
    
    /*
    public void drawString(String s, float x, float y, JustificationInfo info) {
        if (Configuration.isTrue("xr.renderer.replace-missing-characters", false)) {
            s = replaceMissingCharacters(s);
        }
        if (s.length() == 0)
            return;

        ensureFillColor();
        AffineTransform at = (AffineTransform) getTransform().clone();
        at.translate(x, y);
        AffineTransform inverse = normalizeMatrix(at);
        AffineTransform flipper = AffineTransform.getScaleInstance(1, -1);
        inverse.concatenate(flipper);
        inverse.scale(_dotsPerPoint, _dotsPerPoint);
        double[] mx = new double[6];
        inverse.getMatrix(mx);

        _cp.beginText();

        // Check if bold or italic need to be emulated
        boolean resetMode = false;
        FontDescription desc = _font.getFontDescription();
        float fontSize = _font.getSize2D() / _dotsPerPoint;
        cb.setFontAndSize(desc.getFont(), fontSize);
        float b = (float) mx[1];
        float c = (float) mx[2];
        FontSpecification fontSpec = getFontSpecification();
        if (fontSpec != null) {
            int need = ITextFontResolver.convertWeightToInt(fontSpec.fontWeight);
            int have = desc.getWeight();
            if (need > have) {
                cb.setTextRenderingMode(PdfContentByte.TEXT_RENDER_MODE_FILL_STROKE);
                float lineWidth = fontSize * 0.04f; // 4% of font size
                cb.setLineWidth(lineWidth);
                resetMode = true;
                ensureStrokeColor();
            }
            if ((fontSpec.fontStyle == IdentValue.ITALIC) && (desc.getStyle() != IdentValue.ITALIC)) {
                b = 0f;
                c = 0.21256f;
            }
        }
        cb.setTextMatrix((float) mx[0], b, c, (float) mx[3], (float) mx[4], (float) mx[5]);
        if (info == null) {
            _cp.drawString(s);
        } else {
            PdfTextArray array = makeJustificationArray(s, info);
            cb.showText(array);
        }
        if (resetMode) {
            cb.setTextRenderingMode(PdfContentByte.TEXT_RENDER_MODE_FILL);
            cb.setLineWidth(1);
        }
        _cp.endText();
    }
    */
/*
    private PdfTextArray makeJustificationArray(String s, JustificationInfo info) {
        PdfTextArray array = new PdfTextArray();
        int len = s.length();
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            array.add(Character.toString(c));
            if (i != len - 1) {
                float offset;
                if (c == ' ' || c == '\u00a0' || c == '\u3000') {
                    offset = info.getSpaceAdjust();
                } else {
                    offset = info.getNonSpaceAdjust();
                }
                array.add((-offset / _dotsPerPoint) * 1000 / (_font.getSize2D() / _dotsPerPoint));
            }
        }
        return array;
    }
*/
    public AffineTransform getTransform() {
        return _transform;
    }

    private void ensureFillColor() {
        if (!(_color.equals(_fillColor))) {
            _fillColor = _color;

            if (_fillColor instanceof FSRGBColor) {
                FSRGBColor rgb = (FSRGBColor) _fillColor;
                _cp.setFillColor(rgb.getRed(), rgb.getGreen(), rgb.getBlue());
            } else if (_fillColor instanceof FSCMYKColor) {
                FSCMYKColor cmyk = (FSCMYKColor) _fillColor;
                _cp.setFillColor(cmyk.getCyan(), cmyk.getMagenta(), cmyk.getYellow(), cmyk.getBlack());
            }
            else {
                assert(_fillColor instanceof FSRGBColor || _fillColor instanceof FSCMYKColor);
            }
        }
    }

    private void ensureStrokeColor() {
        if (!(_color.equals(_strokeColor))) {
            _strokeColor = _color;

            if (_strokeColor instanceof FSRGBColor) {
                FSRGBColor rgb = (FSRGBColor) _strokeColor;
                _cp.setStrokingColor(rgb.getRed(), rgb.getGreen(), rgb.getBlue());
            } else if (_strokeColor instanceof FSCMYKColor) {
                FSCMYKColor cmyk = (FSCMYKColor) _strokeColor;
                _cp.setStrokingColor(cmyk.getCyan(), cmyk.getMagenta(), cmyk.getYellow(), cmyk.getBlack());
            }
            else {
                assert(_strokeColor instanceof FSRGBColor || _strokeColor instanceof FSCMYKColor);
            }
        }
    }

    public PdfContentStreamAdapter getCurrentPage() {
        return _cp;
    }

    private void followPath(Shape s, int drawType) {
        if (s == null)
            return;

        if (drawType == STROKE) {
            if (!(_stroke instanceof BasicStroke)) {
                s = _stroke.createStrokedShape(s);
                followPath(s, FILL);
                return;
            }
        }
        if (drawType == STROKE) {
            setStrokeDiff(_stroke, _oldStroke);
            _oldStroke = _stroke;
            ensureStrokeColor();
        } else if (drawType == FILL) {
            ensureFillColor();
        }

        PathIterator points;
        if (drawType == CLIP) {
            points = s.getPathIterator(IDENTITY);
        } else {
            points = s.getPathIterator(_transform);
        }
        float[] coords = new float[6];
        int traces = 0;
        while (!points.isDone()) {
            ++traces;
            int segtype = points.currentSegment(coords);
            normalizeY(coords);
            switch (segtype) {
            case PathIterator.SEG_CLOSE:
                _cp.closeSubpath();
                break;

            case PathIterator.SEG_CUBICTO:
                _cp.curveTo(coords[0], coords[1], coords[2], coords[3], coords[4], coords[5]);
                break;

            case PathIterator.SEG_LINETO:
                _cp.lineTo(coords[0], coords[1]);
                break;

            case PathIterator.SEG_MOVETO:
                _cp.moveTo(coords[0], coords[1]);
                break;

            case PathIterator.SEG_QUADTO:
                _cp.curveTo(coords[0], coords[1], coords[2], coords[3]);
                break;
            }
            points.next();
        }

        switch (drawType) {
        case FILL:
            if (traces > 0) {
                if (points.getWindingRule() == PathIterator.WIND_EVEN_ODD)
                    _cp.fillEvenOdd();
                else
                    _cp.fillNonZero();
            }
            break;
        case STROKE:
            if (traces > 0)
                _cp.stroke();
            break;
        default: // drawType==CLIP
            if (traces == 0)
                _cp.addRect(0, 0, 0, 0);
            if (points.getWindingRule() == PathIterator.WIND_EVEN_ODD)
                _cp.clipEvenOdd();
            else
                _cp.clipNonZero();
            _cp.newPath();
        }
    }

    private float normalizeY(float y) {
        return _pageHeight - y;
    }

    private void normalizeY(float[] coords) {
        coords[1] = normalizeY(coords[1]);
        coords[3] = normalizeY(coords[3]);
        coords[5] = normalizeY(coords[5]);
    }

    private void setStrokeDiff(Stroke newStroke, Stroke oldStroke) {
        if (newStroke == oldStroke)
            return;
        if (!(newStroke instanceof BasicStroke))
            return;
        BasicStroke nStroke = (BasicStroke) newStroke;
        boolean oldOk = (oldStroke instanceof BasicStroke);
        BasicStroke oStroke = null;
        if (oldOk)
            oStroke = (BasicStroke) oldStroke;
        if (!oldOk || nStroke.getLineWidth() != oStroke.getLineWidth())
            _cp.setLineWidth(nStroke.getLineWidth());
        if (!oldOk || nStroke.getEndCap() != oStroke.getEndCap()) {
            switch (nStroke.getEndCap()) {
            case BasicStroke.CAP_BUTT:
                _cp.setLineCap(0);
                break;
            case BasicStroke.CAP_SQUARE:
                _cp.setLineCap(2);
                break;
            default:
                _cp.setLineCap(1);
            }
        }
        if (!oldOk || nStroke.getLineJoin() != oStroke.getLineJoin()) {
            switch (nStroke.getLineJoin()) {
            case BasicStroke.JOIN_MITER:
                _cp.setLineJoin(0);
                break;
            case BasicStroke.JOIN_BEVEL:
                _cp.setLineJoin(2);
                break;
            default:
                _cp.setLineJoin(1);
            }
        }
        if (!oldOk || nStroke.getMiterLimit() != oStroke.getMiterLimit())
            _cp.setMiterLimit(nStroke.getMiterLimit());
        boolean makeDash;
        if (oldOk) {
            if (nStroke.getDashArray() != null) {
                if (nStroke.getDashPhase() != oStroke.getDashPhase()) {
                    makeDash = true;
                } else if (!java.util.Arrays.equals(nStroke.getDashArray(), oStroke.getDashArray())) {
                    makeDash = true;
                } else
                    makeDash = false;
            } else if (oStroke.getDashArray() != null) {
                makeDash = true;
            } else
                makeDash = false;
        } else {
            makeDash = true;
        }
        if (makeDash) {
            float dash[] = nStroke.getDashArray();
            if (dash == null)
                _cp.setLineDash(new float[] {}, 0);
            else {
                _cp.setLineDash(dash, nStroke.getDashPhase());
            }
        }
    }

    public void setStroke(Stroke s) {
        _originalStroke = s;
        this._stroke = transformStroke(s);
    }

    private Stroke transformStroke(Stroke stroke) {
        if (!(stroke instanceof BasicStroke))
            return stroke;
        BasicStroke st = (BasicStroke) stroke;
        float scale = (float) Math.sqrt(Math.abs(_transform.getDeterminant()));
        float dash[] = st.getDashArray();
        if (dash != null) {
            for (int k = 0; k < dash.length; ++k)
                dash[k] *= scale;
        }
        return new BasicStroke(st.getLineWidth() * scale, st.getEndCap(), st.getLineJoin(), st.getMiterLimit(), dash, st.getDashPhase()
                * scale);
    }

    public void clip(Shape s) {
        if (s != null) {
            s = _transform.createTransformedShape(s);
            if (_clip == null)
                _clip = new Area(s);
            else
                _clip.intersect(new Area(s));
            followPath(s, CLIP);
        } else {
            assert(s != null);
        }
    }

    public Shape getClip() {
        try {
            return _transform.createInverse().createTransformedShape(_clip);
        } catch (NoninvertibleTransformException e) {
            return null;
        }
    }

    public void setClip(Shape s) {
        _cp.restoreGraphics();
        _cp.saveGraphics();
        if (s != null)
            s = _transform.createTransformedShape(s);
        if (s == null) {
            _clip = null;
        } else {
            _clip = new Area(s);
            followPath(s, CLIP);
        }
        _fillColor = null;
        _strokeColor = null;
        _oldStroke = null;
    }

    public Stroke getStroke() {
        return _originalStroke;
    }
    
    public void realizeImage(PdfBoxImage img) {
        PDImageXObject xobject;
        try {
            if (img.isJpeg()) {
                xobject = JPEGFactory.createFromStream(_writer,
                        new ByteArrayInputStream(img.getBytes()));
            } else {
                BufferedImage buffered = ImageIO.read(new ByteArrayInputStream(
                        img.getBytes()));

                xobject = LosslessFactory.createFromImage(_writer, buffered);
            }
        } catch (IOException e) {
            throw new PdfContentStreamAdapter.PdfException("realizeImage", e);
        }
        img.clearBytes();
        img.setXObject(xobject);
    }

    public void drawImage(FSImage fsImage, int x, int y) {
        PdfBoxImage img = (PdfBoxImage) fsImage;

        PDImageXObject xobject = img.getXObject();

        AffineTransform transformer = (AffineTransform) getTransform().clone();
        transformer.translate(x, y);
        transformer.translate(0, img.getHeight());
        AffineTransform normalized = normalizeMatrix(transformer);
        normalized.scale(img.getWidth(), -img.getHeight());

        double[] mx = new double[6];
        normalized.getMatrix(mx);

        _cp.drawImage(xobject, (float) mx[4], (float) mx[5], (float) mx[0],
                (float) mx[3]);
    }
/*
    private void drawPDFAsImage(PDFAsImage image, int x, int y) {
        URI uri = image.getURI();
        PdfReader reader = null;

        try {
            reader = getReader(uri);
        } catch (IOException e) {
            throw new XRRuntimeException("Could not load " + uri + ": " + e.getMessage(), e);
        }

        PdfImportedPage page = getWriter().getImportedPage(reader, 1);

        AffineTransform at = AffineTransform.getTranslateInstance(x, y);
        at.translate(0, image.getHeightAsFloat());
        at.scale(image.getWidthAsFloat(), image.getHeightAsFloat());

        AffineTransform inverse = normalizeMatrix(_transform);
        AffineTransform flipper = AffineTransform.getScaleInstance(1, -1);
        inverse.concatenate(at);
        inverse.concatenate(flipper);

        double[] mx = new double[6];
        inverse.getMatrix(mx);

        mx[0] = image.scaleWidth();
        mx[3] = image.scaleHeight();

        _currentPage.restoreState();
        _currentPage.addTemplate(page, (float) mx[0], (float) mx[1], (float) mx[2], (float) mx[3], (float) mx[4], (float) mx[5]);
        _currentPage.saveState();
    }

    public PdfReader getReader(URI uri) throws IOException {
        PdfReader result = (PdfReader) _readerCache.get(uri);
        if (result == null) {
            result = new PdfReader(getSharedContext().getUserAgentCallback().getBinaryResource(uri.toString()));
            _readerCache.put(uri, result);
        }
        return result;
    }
*/
    public float getDotsPerPoint() {
        return _dotsPerPoint;
    }

    public void start(Document doc) {
        _linkManager = new PdfBoxLinkManager(_sharedContext, _dotsPerPoint, _root, this);
        loadBookmarks(doc);
        loadMetadata(doc);
    }

    public void finish(RenderingContext c, Box root) {
        _linkManager.processLinks();
        writeOutline(c, root);
    }

    private void writeOutline(RenderingContext c, Box root) {
        if (_bookmarks.size() > 0) {
            // TODO: .setViewerPreferences(PdfWriter.PageModeUseOutlines);
    
            PDDocumentOutline outline = new PDDocumentOutline();
            _writer.getDocumentCatalog().setDocumentOutline( outline );
            
            writeBookmarks(c, root, outline, _bookmarks);
        }
    }

    private void writeBookmarks(RenderingContext c, Box root, PDOutlineNode parent, List bookmarks) {
        for (Iterator i = bookmarks.iterator(); i.hasNext();) {
            Bookmark bookmark = (Bookmark) i.next();
            writeBookmark(c, root, parent, bookmark);
        }
    }

    int getPageRefY(Box box) {
        if (box instanceof InlineLayoutBox) {
            InlineLayoutBox iB = (InlineLayoutBox) box;
            return iB.getAbsY() + iB.getBaseline();
        } else {
            return box.getAbsY();
        }
    }

    private void writeBookmark(RenderingContext c, Box root, PDOutlineNode parent, Bookmark bookmark) {
        String href = bookmark.getHRef();
        PDPageXYZDestination target = null;
        if (href.length() > 0 && href.charAt(0) == '#') {
            Box box = _sharedContext.getBoxById(href.substring(1));
            if (box != null) {
                PageBox page = root.getLayer().getPage(c, getPageRefY(box));
                int distanceFromTop = page.getMarginBorderPadding(c, CalculatedStyle.TOP);
                distanceFromTop += box.getAbsY() - page.getTop();

                target = new PDPageXYZDestination();
                target.setTop((int) normalizeY(distanceFromTop / _dotsPerPoint));
                target.setPage(_writer.getPage(_startPageNo + page.getPageNo()));
            }
        }

        PDOutlineItem outline = new PDOutlineItem();
        outline.setDestination(target == null ? _defaultDestination : target);
        outline.setTitle(bookmark.getName());
        parent.addLast(outline);
        writeBookmarks(c, root, outline, bookmark.getChildren());
    }

    private void loadBookmarks(Document doc) {
        Element head = DOMUtil.getChild(doc.getDocumentElement(), "head");
        if (head != null) {
            Element bookmarks = DOMUtil.getChild(head, "bookmarks");
            if (bookmarks != null) {
                List l = DOMUtil.getChildren(bookmarks, "bookmark");
                if (l != null) {
                    for (Iterator i = l.iterator(); i.hasNext();) {
                        Element e = (Element) i.next();
                        loadBookmark(null, e);
                    }
                }
            }
        }
    }

    private void loadBookmark(Bookmark parent, Element bookmark) {
        Bookmark us = new Bookmark(bookmark.getAttribute("name"), bookmark.getAttribute("href"));
        if (parent == null) {
            _bookmarks.add(us);
        } else {
            parent.addChild(us);
        }
        List l = DOMUtil.getChildren(bookmark, "bookmark");
        if (l != null) {
            for (Iterator i = l.iterator(); i.hasNext();) {
                Element e = (Element) i.next();
                loadBookmark(us, e);
            }
        }
    }

    private static class Bookmark {
        private String _name;
        private String _HRef;

        private List _children;

        public Bookmark() {
        }

        public Bookmark(String name, String href) {
            _name = name;
            _HRef = href;
        }

        public String getHRef() {
            return _HRef;
        }

        public void setHRef(String href) {
            _HRef = href;
        }

        public String getName() {
            return _name;
        }

        public void setName(String name) {
            _name = name;
        }

        public void addChild(Bookmark child) {
            if (_children == null) {
                _children = new ArrayList();
            }
            _children.add(child);
        }

        public List getChildren() {
            return _children == null ? Collections.EMPTY_LIST : _children;
        }
    }

    // Metadata methods

    // Methods to load and search a document's metadata

    /**
     * Appends a name/content metadata pair to this output device. A name or
     * content value of null will be ignored.
     * 
     * @param name
     *            the name of the metadata element to add.
     * @return the content value for this metadata.
     */
    public void addMetadata(String name, String value) {
        if ((name != null) && (value != null)) {
            Metadata m = new Metadata(name, value);
            _metadata.add(m);
        }
    }

    /**
     * Searches the metadata name/content pairs of the current document and
     * returns the content value from the first pair with a matching name. The
     * search is case insensitive.
     * 
     * @param name
     *            the metadata element name to locate.
     * @return the content value of the first found metadata element; otherwise
     *         null.
     */
    public String getMetadataByName(String name) {
        if (name != null) {
            for (int i = 0, len = _metadata.size(); i < len; i++) {
                Metadata m = (Metadata) _metadata.get(i);
                if ((m != null) && m.getName().equalsIgnoreCase(name)) {
                    return m.getContent();
                }
            }
        }
        return null;
    }

    /**
     * Searches the metadata name/content pairs of the current document and
     * returns any content values with a matching name in an ArrayList. The
     * search is case insensitive.
     * 
     * @param name
     *            the metadata element name to locate.
     * @return an ArrayList with matching content values; otherwise an empty
     *         list.
     */
    public ArrayList getMetadataListByName(String name) {
        ArrayList result = new ArrayList();
        if (name != null) {
            for (int i = 0, len = _metadata.size(); i < len; i++) {
                Metadata m = (Metadata) _metadata.get(i);
                if ((m != null) && m.getName().equalsIgnoreCase(name)) {
                    result.add(m.getContent());
                }
            }
        }
        return result;
    }

    /**
     * Locates and stores all metadata values in the document head that contain
     * name/content pairs. If there is no pair with a name of "title", any
     * content in the title element is saved as a "title" metadata item.
     * 
     * @param doc
     *            the Document level node of the parsed xhtml file.
     */
    private void loadMetadata(Document doc) {
        Element head = DOMUtil.getChild(doc.getDocumentElement(), "head");
        if (head != null) {
            List l = DOMUtil.getChildren(head, "meta");
            if (l != null) {
                for (Iterator i = l.iterator(); i.hasNext();) {
                    Element e = (Element) i.next();
                    String name = e.getAttribute("name");
                    if (name != null) { // ignore non-name metadata data
                        String content = e.getAttribute("content");
                        Metadata m = new Metadata(name, content);
                        _metadata.add(m);
                    }
                }
            }
            // If there is no title meta data attribute, use the document title.
            String title = getMetadataByName("title");
            if (title == null) {
                Element t = DOMUtil.getChild(head, "title");
                if (t != null) {
                    title = DOMUtil.getText(t).trim();
                    Metadata m = new Metadata("title", title);
                    _metadata.add(m);
                }
            }
        }
    }

    /**
     * Replaces all copies of the named metadata with a single value. A a new
     * value of null will result in the removal of all copies of the named
     * metadata. Use <code>addMetadata</code> to append additional values with
     * the same name.
     * 
     * @param name
     *            the metadata element name to locate.
     * @return the new content value for this metadata (null to remove all
     *         instances).
     */
    public void setMetadata(String name, String value) {
        if (name != null) {
            boolean remove = (value == null); // removing all instances of name?
            int free = -1; // first open slot in array
            for (int i = 0, len = _metadata.size(); i < len; i++) {
                Metadata m = (Metadata) _metadata.get(i);
                if (m != null) {
                    if (m.getName().equalsIgnoreCase(name)) {
                        if (!remove) {
                            remove = true; // remove all other instances
                            m.setContent(value);
                        } else {
                            _metadata.set(i, null);
                        }
                    }
                } else if (free == -1) {
                    free = i;
                }
            }
            if (!remove) { // not found?
                Metadata m = new Metadata(name, value);
                if (free == -1) { // no open slots?
                    _metadata.add(m);
                } else {
                    _metadata.set(free, m);
                }
            }
        }
    }

    // Class for storing metadata element name/content pairs from the head
    // section of an xhtml document.
    private static class Metadata {
        private String _name;
        private String _content;

        public Metadata(String name, String content) {
            _name = name;
            _content = content;
        }

        public String getContent() {
            return _content;
        }

        public void setContent(String content) {
            _content = content;
        }

        public String getName() {
            return _name;
        }

        public void setName(String name) {
            _name = name;
        }
    }

    // Metadata end

    public SharedContext getSharedContext() {
        return _sharedContext;
    }

    public void setSharedContext(SharedContext sharedContext) {
        _sharedContext = sharedContext;
        sharedContext.getCss().setSupportCMYKColors(true);
    }

    public void setRoot(Box root) {
        _root = root;
    }

    public int getStartPageNo() {
        return _startPageNo;
    }

    public void setStartPageNo(int startPageNo) {
        _startPageNo = startPageNo;
    }

    public void drawSelection(RenderingContext c, InlineText inlineText) {
        throw new UnsupportedOperationException();
    }

    public boolean isSupportsSelection() {
        return false;
    }

    public boolean isSupportsCMYKColors() {
        return true;
    }

    public List findPagePositionsByID(CssContext c, Pattern pattern) {
        Map idMap = _sharedContext.getIdMap();
        if (idMap == null) {
            return Collections.EMPTY_LIST;
        }

        List result = new ArrayList();
        for (Iterator i = idMap.entrySet().iterator(); i.hasNext();) {
            Map.Entry entry = (Entry) i.next();
            String id = (String) entry.getKey();
            if (pattern.matcher(id).find()) {
                Box box = (Box) entry.getValue();
                PagePosition pos = calcPDFPagePosition(c, id, box);
                if (pos != null) {
                    result.add(pos);
                }
            }
        }

        Collections.sort(result, new Comparator() {
            public int compare(Object arg0, Object arg1) {
                PagePosition p1 = (PagePosition) arg0;
                PagePosition p2 = (PagePosition) arg1;
                return p1.getPageNo() - p2.getPageNo();
            }
        });

        return result;
    }

    private PagePosition calcPDFPagePosition(CssContext c, String id, Box box) {
        PageBox page = _root.getLayer().getLastPage(c, box);
        if (page == null) {
            return null;
        }

        float x = box.getAbsX() + page.getMarginBorderPadding(c, CalculatedStyle.LEFT);
        float y = (page.getBottom() - (box.getAbsY() + box.getHeight())) + page.getMarginBorderPadding(c, CalculatedStyle.BOTTOM);
        x /= _dotsPerPoint;
        y /= _dotsPerPoint;

        PagePosition result = new PagePosition();
        result.setId(id);
        result.setPageNo(page.getPageNo());
        result.setX(x);
        result.setY(y);
        result.setWidth(box.getEffectiveWidth() / _dotsPerPoint);
        result.setHeight(box.getHeight() / _dotsPerPoint);

        return result;
    }

    public void setRenderingContext(RenderingContext result) {
        _renderingContext = result;
    }

    public void setBidiReorderer(BidiReorderer reorderer) {
        _reorderer = reorderer;
    }

    @Override
    public void drawText(RenderingContext c, String text, float x, float y) {
    }

    @Override
    public void saveState() {
        _cp.saveGraphics();
    }

    @Override
    public void restoreState() {
        _cp.restoreGraphics();
    }

    @Override
    public void setTransform(AffineTransform transform) {
        _transform = transform;
        
    }

    @Override
    public void setPaint(Paint paint) {
        if (paint instanceof Color) {
            Color c = (Color) paint;
            this.setColor(new FSRGBColor(c.getRed(), c.getGreen(), c.getBlue()));
        } else {
            XRLog.render(Level.WARNING, "Unknown paint: " + paint.getClass().getCanonicalName());
        }
    }

    @Override
    public void setAlpha(int alpha) {
        
    }

    @Override
    public void setRawClip(Shape s) {
        _clip = new Area(s);
        followPath(s, CLIP);
    }

    @Override
    public void rawClip(Shape s) {
        if (_clip == null)
            _clip = new Area(s);
        else
            _clip.intersect(new Area(s));
        followPath(s, CLIP);
    }
    
    @Override
    public Shape getRawClip() {
        return _clip;
    }
}
