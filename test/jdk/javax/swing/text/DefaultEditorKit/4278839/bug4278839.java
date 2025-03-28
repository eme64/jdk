/*
 * Copyright (c) 2011, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * @test
 * @key headful
 * @bug 4278839 8233634
 * @summary Incorrect cursor movement between words at the end of line
 * @library ../../../regtesthelpers
 * @build Util
 * @run main bug4278839
 */

import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.awt.event.InputEvent;
import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

public class bug4278839 {

    private static boolean passed = true;
    private static JTextArea area;
    private static Robot robo;
    private static JFrame frame;

    public static void main(String[] args) throws Exception {
        int caret;
        try {

            robo = new Robot();
            robo.setAutoDelay(100);

            SwingUtilities.invokeAndWait(() -> createAndShowGUI());

            robo.waitForIdle();
            robo.delay(1000);

            clickMouse();
            robo.waitForIdle();
            robo.delay(250);

            area.setCaretPosition(0);
            robo.waitForIdle();
            robo.delay(250);

            passed &= (caret = moveCaret(true)) == 1;
            System.out.println(" passed " + passed +
                               " Expected position 1 actual position " + caret);

            passed &= (caret = moveCaret(true)) == 5;
            System.out.println(" passed " + passed +
                               " Expected position 5 actual position " + caret);

            passed &= (caret = moveCaret(true)) == 8;
            System.out.println(" passed " + passed +
                               " Expected position 8 actual position " + caret);

            passed &= (caret = moveCaret(true)) == 9;
            System.out.println(" passed " + passed +
                               " Expected position 9 actual position " + caret);

            passed &= (caret = moveCaret(true)) == 13;
            System.out.println(" passed " + passed +
                               " Expected position 13 actual position " + caret);

            passed &= (caret = moveCaret(true)) == 16;
            System.out.println(" passed " + passed +
                               " Expected position 16 actual position " + caret);

            passed &= (caret = moveCaret(true)) == 17;
            System.out.println(" passed " + passed +
                               " Expected position 17 actual position " + caret);

            passed &= (caret = moveCaret(false)) == 16;
            System.out.println(" passed " + passed +
                               " Expected position 16 actual position " + caret);

            passed &= (caret = moveCaret(false)) == 13;
            System.out.println(" passed " + passed +
                               " Expected position 13 actual position " + caret);

            passed &= (caret = moveCaret(false)) == 9;
            System.out.println(" passed " + passed +
                               " Expected position 9 actual position " + caret);

            passed &= (caret = moveCaret(false)) == 8;
            System.out.println(" passed " + passed +
                               " Expected position 8 actual position " + caret);

            passed &= (caret = moveCaret(false)) == 5;
            System.out.println(" passed " + passed +
                               " Expected position 5 actual position " + caret);

            passed &= (caret = moveCaret(false)) == 1;
            System.out.println(" passed " + passed +
                               " Expected position 1 actual position " + caret);

            passed &= (caret = moveCaret(false)) == 0;
            System.out.println(" passed " + passed +
                               " Expected position 0 actual position " + caret);

        } catch (Exception e) {
            throw new RuntimeException("Test failed because of an exception:",
                    e);
        } finally {
            if (frame != null) {
                SwingUtilities.invokeAndWait(() -> frame.dispose());
            }
        }

        if (!passed) {
            throw new RuntimeException("Test failed.");
        }
    }

    private static int moveCaret(boolean right) throws Exception {
        Util.hitKeys(robo, getCtrlKey(),
                right ? KeyEvent.VK_RIGHT : KeyEvent.VK_LEFT);
        robo.waitForIdle();
        robo.delay(250);

        final int[] result = new int[1];

        SwingUtilities.invokeAndWait(() -> {
            result[0] = area.getCaretPosition();
        });

        return result[0];
    }

    private static void clickMouse() throws Exception {
        final Rectangle result[] = new Rectangle[1];

        SwingUtilities.invokeAndWait(() -> {
            result[0] = new Rectangle(area.getLocationOnScreen(), area.getSize());
        });

        Rectangle rect = result[0];

        robo.mouseMove(rect.x + rect.width / 2, rect.y + rect.width / 2);
        robo.waitForIdle();
        robo.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robo.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        robo.waitForIdle();
    }

    /**
     * Gets a control key related to the used Look & Feel
     * Returns VK_ALT for Aqua and VK_CONTROL for others
     */
    public static int getCtrlKey() {

        if ("Aqua".equals(UIManager.getLookAndFeel().getID())) {
            return KeyEvent.VK_ALT;
        }

        return KeyEvent.VK_CONTROL;
    }

    private static void createAndShowGUI() {
        frame = new JFrame();
        frame.setTitle("Bug# 4278839");
        frame.setSize(200, 200);
        frame.setLocationRelativeTo(null);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        area = new JTextArea("\naaa bbb\nccc ddd\n");
        frame.getContentPane().add(new JScrollPane(area));
        frame.setVisible(true);
    }
}
