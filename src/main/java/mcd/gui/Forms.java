package mcd.gui;

import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.Insets;

/** 대화 상자들이 같이 쓰는 2열 폼 배치. */
final class Forms {

    private Forms() {}

    static int addRow(JPanel form, int row, Object label, Component field) {
        form.add(label instanceof String text ? new JLabel(text) : (Component) label, constraints(0, row));
        GridBagConstraints c = constraints(1, row);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        form.add(field, c);
        return row + 1;
    }

    static int addNote(JPanel form, int row, String text) {
        form.add(new JLabel("<html><div style='width:320px;color:gray'>" + text + "</div></html>"), constraints(1, row));
        return row + 1;
    }

    static int addWide(JPanel form, int row, Component component) {
        GridBagConstraints c = constraints(0, row);
        c.gridwidth = 2;
        form.add(component, c);
        return row + 1;
    }

    private static GridBagConstraints constraints(int x, int y) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = x;
        c.gridy = y;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(4, 4, 4, 4);
        return c;
    }
}
