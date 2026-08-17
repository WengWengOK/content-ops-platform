package com.contentops.publish.render;

/**
 * 卡片溢出测量器：判断单张卡片的真实内容高度是否超过画板预算。
 *
 * <p>P1 升级：优先用渲染服务（无头浏览器真实 DOM 测量，R1 溢出检测思路）；
 * 渲染服务不可用时回退到字符/块级估算。
 */
public interface CardMeasurer {

    /** 单张卡片测量结果。 */
    record OverflowResult(int overflowPx, boolean measured) {
        static OverflowResult estimate(int overflowPx) {
            return new OverflowResult(overflowPx, false);
        }
    }

    /**
     * 测量卡片 HTML 内容相对画板的溢出像素。
     *
     * @param cardHtml        单张卡片的完整 HTML
     * @param width           画板宽度
     * @param height          画板高度
     * @param estimatedOverflow 调用方基于块级估算的溢出值（测量不可用时回退）
     */
    OverflowResult measureOverflow(String cardHtml, int width, int height, int estimatedOverflow);
}
