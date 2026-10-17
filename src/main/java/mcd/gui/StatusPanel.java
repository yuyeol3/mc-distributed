package mcd.gui;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.UIManager;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/** 메인 창의 상태 카드: 지금 무슨 상태인지, 마인크래프트에서 어디로 접속하는지, 최근 메시지. */
final class StatusPanel extends JPanel {

    private static final int ARC = 16;

    private final JLabel dot = new JLabel("●");
    private final JLabel title = new JLabel(" ");
    private final JLabel detail = new JLabel(" ");
    private final JLabel message = new JLabel(" ");

    StatusPanel(String address) {
        super(new BorderLayout());
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));

        dot.setFont(dot.getFont().deriveFont(18f));
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20f));
        detail.setForeground(UIManager.getColor("Label.disabledForeground"));
        message.setForeground(UIManager.getColor("Label.disabledForeground"));
        message.setFont(message.getFont().deriveFont(message.getFont().getSize2D() - 1f));

        JPanel heading = row(dot, title);
        JLabel how = new JLabel("마인크래프트 → 멀티플레이 → 서버 추가 → 주소");
        JLabel addr = new JLabel(address);
        addr.setFont(new Font(Font.MONOSPACED, Font.BOLD, 15));
        JButton copy = new JButton("주소 복사");
        copy.putClientProperty("JButton.buttonType", "borderless");
        // 노드를 켜는 동안 다른 컨트롤이 잠깐 꺼지면 포커스가 이 버튼으로 와서 테두리가 남는다. 마우스용 보조 버튼이다.
        copy.setFocusable(false);
        copy.addActionListener(e -> DeployKeyDialog.copyToClipboard(address));
        JPanel connect = row(how, addr, copy);

        JPanel column = new JPanel();
        column.setOpaque(false);
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        for (Component c : new Component[] {heading, indent(detail), Box.createVerticalStrut(12), connect, Box.createVerticalStrut(6), message}) {
            if (c instanceof JPanel p) p.setAlignmentX(LEFT_ALIGNMENT);
            if (c instanceof JLabel l) l.setAlignmentX(LEFT_ALIGNMENT);
            column.add(c);
        }
        add(column, BorderLayout.CENTER);
    }

    /** 상태를 바꾼다(EDT). */
    void show(Color color, String titleText, String detailText) {
        dot.setForeground(color);
        title.setText(titleText);
        detail.setText(detailText == null || detailText.isEmpty() ? " " : detailText);
    }

    /** mcd의 최근 안내 메시지(EDT). */
    void setMessage(String text) {
        message.setText(text);
        message.setToolTipText(text);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(UIManager.getColor("TextField.background"));
        g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, ARC, ARC);
        g2.setColor(UIManager.getColor("Component.borderColor"));
        g2.setStroke(new BasicStroke(1f));
        g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, ARC, ARC);
        g2.dispose();
        super.paintComponent(g);
    }

    private static JPanel row(Component... components) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row.setOpaque(false);
        for (Component c : components) row.add(c);
        return row;
    }

    /** 제목 글자 아래에 맞춰 들여 쓴다(점 너비만큼). */
    private JPanel indent(JLabel label) {
        JPanel p = row(Box.createHorizontalStrut(dot.getPreferredSize().width + 6), label);
        return p;
    }
}
