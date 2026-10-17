package com.freshmeat.util;

import org.springframework.util.StringUtils;

/**
 * 文件与图片访问地址公共处理工具（前后端文件路径统一规范）
 *
 * <p>规范约定：
 * 1. 数据库存储：统一只存储相对路径（如 {@code /product/20261017/xxx.jpg}、{@code /shop/xxx.jpg}、{@code /avatar/user_1.jpg}），
 *    绝不将域名、IP、端口或上下文前缀（如 {@code http://localhost:8081/order_file}、{@code /order_file}）硬编码写入数据库；
 * 2. 入库过滤：入库前调用 {@link #toRelative(String)}，自动剥离任何携带的 baseServer 域名或前缀，还原为纯相对路径；
 * 3. 微信前端展示：微信前端通过公共工具函数 {@code formatImageUrl} 动态拼接 {@code file.base-server} 进行渲染展示。
 * </p>
 */
public final class FileUrlUtil {

    private FileUrlUtil() {
    }

    /**
     * 将任意形态的文件/图片地址转换为纯相对路径（用于落库保存）。
     *
     * <p>转换规则：
     * 1. 若为本系统上传的文件（无论带不带域名、端口或上下文前缀，只要属于 /product/、/avatar/、/category/、/order_file/、/meat_file/、/uploads/ 等），
     *    一律剔除域名和前缀，提炼为统一的纯相对路径（如 {@code /product/20261017/xxx.jpg}、{@code /avatar/20261017/xxx.jpg}）；
     * 2. 若为真正的第三方外链（如微信用户官方头像 {@code https://thirdwx.qlogo.cn/...} 或外部图床），完整保留原 URL 不作裁切；
     * 3. 相对路径统一标准化为前导正斜杠 {@code /}。
     * </p>
     *
     * @param path 原始路径（可能是绝对 URL，也可能是相对路径）
     * @return 纯相对路径（如 /product/20261017/xxx.jpg）或第三方完整外链
     */
    public static String toRelative(String path) {
        if (!StringUtils.hasText(path)) {
            return "";
        }
        String p = path.trim().replace('\\', '/');
        if (!StringUtils.hasText(p)) {
            return "";
        }

        // 如果是绝对 URL (http:// 或 https://)
        if (isAbsoluteUrl(p)) {
            // 判断是否包含本系统内部典型模块目录或前缀
            String lower = p.toLowerCase();
            int matchIdx = -1;
            String matchedPrefix = "";

            String[] internalKeywords = new String[] {
                    "/order_file/", "/meat_file/", "/meta_file/", "/api/uploads/", "/uploads/",
                    "/product/", "/avatar/", "/category/", "/shop/"
            };

            for (String kw : internalKeywords) {
                int idx = lower.indexOf(kw);
                if (idx >= 0 && (matchIdx == -1 || idx < matchIdx)) {
                    matchIdx = idx;
                    matchedPrefix = kw;
                }
            }

            if (matchIdx >= 0) {
                // 是本系统的内部文件（可能带域名 http://localhost:8081/meat_file/product/...）
                // 截取内部模块路径起始部分
                String sub = p.substring(matchIdx);
                // 剥离外层包裹的上下文前缀
                if (sub.startsWith("/order_file/")) {
                    sub = sub.substring("/order_file".length());
                } else if (sub.startsWith("/meat_file/")) {
                    sub = sub.substring("/meat_file".length());
                } else if (sub.startsWith("/meta_file/")) {
                    sub = sub.substring("/meta_file".length());
                } else if (sub.startsWith("/api/uploads/")) {
                    sub = sub.substring("/api/uploads".length());
                } else if (sub.startsWith("/uploads/")) {
                    sub = sub.substring("/uploads".length());
                }
                return sub.startsWith("/") ? sub : "/" + sub;
            }

            // 若包含 localhost / 127.0.0.1 等本地开发服务器标识，也剥离域名
            if (lower.contains("://localhost") || lower.contains("://127.0.0.1")) {
                int slashIndex = p.indexOf("://");
                int pathIndex = p.indexOf('/', slashIndex + 3);
                if (pathIndex > 0) {
                    p = p.substring(pathIndex);
                } else {
                    return "";
                }
            } else {
                // 属于微信头像（如 *.qlogo.cn）或其他纯第三方外链，保留完整绝对 URL
                return p;
            }
        }

        // 剥离可能存在的上下文前缀（如 /order_file、/meat_file、/api/uploads 等）
        if (p.startsWith("/order_file/")) {
            p = p.substring("/order_file".length());
        } else if (p.equals("/order_file")) {
            return "";
        } else if (p.startsWith("/meat_file/")) {
            p = p.substring("/meat_file".length());
        } else if (p.equals("/meat_file")) {
            return "";
        } else if (p.startsWith("/meta_file/")) {
            p = p.substring("/meta_file".length());
        } else if (p.equals("/meta_file")) {
            return "";
        } else if (p.startsWith("/api/uploads/")) {
            p = p.substring("/api/uploads".length());
        } else if (p.startsWith("/uploads/")) {
            p = p.substring("/uploads".length());
        } else if (p.startsWith("/file/")) {
            p = p.substring("/file".length());
        }

        // 判断是否是 emoji 或非文件路径（不含斜杠且不含常见文件扩展名）
        if (!p.contains("/") && !p.contains(".")) {
            return p;
        }

        return p.startsWith("/") ? p : "/" + p;
    }

    /**
     * 相对路径 -> 完整访问 URL（可用于出参或后台预览）。
     *
     * @param path       相对路径（如 /product/xxx.jpg）或已是完整 URL
     * @param baseServer 文件访问基础地址，如 http://localhost:8081/order_file
     * @return 完整访问地址
     */
    public static String toAbsolute(String path, String baseServer) {
        if (!StringUtils.hasText(path)) {
            return "";
        }
        String p = path.trim();
        if (!StringUtils.hasText(p)) {
            return "";
        }
        // 如果已经是绝对 URL，直接返回
        if (isAbsoluteUrl(p)) {
            return p;
        }
        String base = StringUtils.hasText(baseServer) ? trimSlash(baseServer.trim()) : "";
        if (!StringUtils.hasText(base)) {
            return p.startsWith("/") ? p : "/" + p;
        }
        return base + (p.startsWith("/") ? p : "/" + p);
    }

    /**
     * 是否是绝对 URL
     */
    public static boolean isAbsoluteUrl(String url) {
        return url != null && url.length() > 8 && (url.regionMatches(true, 0, "http://", 0, 7)
                || url.regionMatches(true, 0, "https://", 0, 8));
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
