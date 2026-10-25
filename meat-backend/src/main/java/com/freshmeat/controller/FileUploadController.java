package com.freshmeat.controller;

import cn.hutool.core.io.FileUtil;
import com.freshmeat.annotation.RequireAdmin;
import com.freshmeat.common.Result;
import com.freshmeat.config.FileConfigProperties;
import com.freshmeat.exception.BusinessException;
import com.freshmeat.util.ImageCompressUtil;
import com.freshmeat.util.SecurityContextUtil;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 文件与图片上传控制器
 *
 * <p>核心规范：
 * 1. 等比无损压缩：统一调用 {@link ImageCompressUtil} 进行等比降采样与现代 WebP 高保真压缩；
 * 2. 路径语义化与扁平化：完全移除年月日（yyyyMMdd）等临时路径；
 *    - 头像路径按用户 ID 拼接（如 /avatar/{userId}/{md5}.webp）；
 *    - 商品图片统一存放于 /product/{md5}.webp；
 * 3. 幂等覆盖机制：文件名为内容 MD5 散列值，相同内容的图片直接原地覆盖，防止磁盘碎片与重复占用。
 * </p>
 */
@Api(tags = "文件与图片上传模块")
@Slf4j
@RestController
@RequestMapping("/file")
@RequiredArgsConstructor
public class FileUploadController {

    private final FileConfigProperties fileConfigProperties;

    /** 允许上传的图片扩展名 */
    private static final String[] ALLOWED_EXT = { "jpg", "jpeg", "png", "gif", "webp", "bmp" };

    /** 单张图片大小上限：10MB */
    private static final long MAX_SIZE = 10L * 1024 * 1024;

    /** 允许删除的合法路径正则，防范路径穿越 */
    private static final Pattern SAFE_STORED_PATH = Pattern.compile(
            "^/(product|avatar|category|shop|other)/[a-zA-Z0-9_/-]*[0-9a-f]{32}\\.(jpg|jpeg|png|gif|webp|bmp)$");

    @ApiOperation(value = "上传图片文件", notes = "用于头像、鲜肉商品主图等上传，支持等比无损压缩及MD5去重覆盖")
    @PostMapping("/upload")
    public Result<Map<String, String>> upload(
            @ApiParam(value = "文件二进制内容", required = true) @RequestParam("file") MultipartFile file,
            @ApiParam(value = "所属业务模块（product/avatar/category/other）") @RequestParam(value = "module", required = false) String module,
            @ApiParam(value = "业务类型（兼容 order 系统传参）") @RequestParam(value = "bizType", required = false) String bizType,
            @ApiParam(value = "用户ID（可选，头像上传时优先使用）") @RequestParam(value = "userId", required = false) String paramUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            HttpServletRequest request) {

        // 安全校验：商品/分类/店铺等业务图片仅限登录管理员上传；
        // 头像允许未登录上传（微信首次授权登录时，头像先于登录态上传）
        requireUploadPermission(module, bizType);

        if (file == null || file.isEmpty()) {
            return Result.error("上传文件不能为空");
        }

        try {
            Map<String, String> data = storeOne(file, module, bizType, paramUserId, headerUserId);
            return Result.success("上传成功", data);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (IOException e) {
            log.error("文件保存失败: {}", e.getMessage(), e);
            return Result.error("文件保存失败：" + e.getMessage());
        }
    }

    @ApiOperation(value = "批量上传图片文件", notes = "支持多张图片批量压缩落盘")
    @PostMapping("/upload-batch")
    public Result<List<Map<String, String>>> uploadBatch(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "module", required = false) String module,
            @RequestParam(value = "bizType", required = false) String bizType,
            @RequestParam(value = "userId", required = false) String paramUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId) {

        // 安全校验：批量上传同样仅头像允许未登录
        requireUploadPermission(module, bizType);

        if (files == null || files.length == 0) {
            return Result.error("上传文件不能为空");
        }

        List<Map<String, String>> list = new ArrayList<>();
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            try {
                list.add(storeOne(file, module, bizType, paramUserId, headerUserId));
            } catch (IllegalArgumentException e) {
                return Result.error(e.getMessage());
            } catch (IOException e) {
                log.error("批量上传失败: {}", file.getOriginalFilename(), e);
                return Result.error("文件上传失败：" + e.getMessage());
            }
        }
        if (list.isEmpty()) {
            return Result.error("上传文件不能为空");
        }
        return Result.success(list);
    }

    @ApiOperation(value = "删除已上传的图片文件", notes = "物理删除磁盘上存储的图片")
    @RequireAdmin
    @DeleteMapping("/delete")
    public Result<Void> delete(@RequestParam("path") String path) {
        if (!StringUtils.hasText(path)) {
            return Result.error("文件地址不能为空");
        }
        String relative = path.trim().replace('\\', '/');
        if (!relative.startsWith("/")) {
            relative = "/" + relative;
        }

        if (!SAFE_STORED_PATH.matcher(relative).matches()) {
            return Result.error("非法的文件地址");
        }

        String baseUploadPath = resolveBaseUploadPath();
        Path root = Paths.get(baseUploadPath).toAbsolutePath().normalize();
        Path target = root.resolve(relative.substring(1)).normalize();
        if (!target.startsWith(root)) {
            return Result.error("非法的文件地址，禁止跨目录操作");
        }

        try {
            boolean deleted = Files.deleteIfExists(target);
            log.info("[file:delete] path={}, deleted={}", relative, deleted);
        } catch (IOException e) {
            log.error("文件删除失败: {}", relative, e);
            return Result.error("文件删除失败");
        }
        return Result.success();
    }

    /**
     * 校验、等比压缩并落盘单个文件
     */
    private Map<String, String> storeOne(
            MultipartFile file,
            String module,
            String bizType,
            String paramUserId,
            String headerUserId) throws IOException {

        String originalFilename = file.getOriginalFilename();
        String ext = FileUtil.extName(originalFilename);
        if (!StringUtils.hasText(ext)) {
            ext = "jpg";
        }
        if (!isAllowed(ext)) {
            throw new IllegalArgumentException("仅支持 " + String.join("/", ALLOWED_EXT) + " 格式的图片");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new IllegalArgumentException("图片大小不能超过 10MB");
        }

        String baseUploadPath = resolveBaseUploadPath();

        // 1. 确定业务模块及二级存储目录（去除日期路径）
        String cleanModule = resolveCleanModule(module, bizType);
        String subPath = resolveSubDir(cleanModule, paramUserId, headerUserId);

        Path targetDir = Paths.get(baseUploadPath, subPath);
        Files.createDirectories(targetDir);

        // 2. 调用公共压缩方法：等比无损压缩为高质量 WebP（降级 JPG）
        byte[] rawBytes = file.getBytes();
        ImageCompressUtil.ProcessedImage processed = ImageCompressUtil.compress(rawBytes, ext);

        // 3. 文件名以图片内容的 MD5 命名，相同图片自动覆盖
        String md5 = processed.getMd5();
        String outputExt = processed.getFormat();
        String newFilename = md5 + "." + outputExt;

        Path destPath = targetDir.resolve(newFilename);
        File destFile = destPath.toFile();

        boolean isOverwrite = destFile.exists();
        // 覆盖写入（Files.write 默认行为：CREATE, TRUNCATE_EXISTING, WRITE）
        Files.write(destPath, processed.getBytes());

        // 4. 构建纯相对路径与完整响应结果
        String relativePath = "/" + subPath.replace(File.separatorChar, '/') + "/" + newFilename;
        String baseServer = fileConfigProperties.getBaseServer();
        String url = StringUtils.hasText(baseServer) ? trimSlash(baseServer) + relativePath : relativePath;

        String ratioStr = rawBytes.length > 0
                ? String.format("%.1f%%", (double) processed.getBytes().length / rawBytes.length * 100)
                : "100.0%";

        log.info("[file:upload] module={}, originalSize={}B, compressedSize={}B (ratio={}), width={}px, height={}px, overwrite={}, path={}",
                cleanModule, rawBytes.length, processed.getBytes().length, ratioStr,
                processed.getWidth(), processed.getHeight(), isOverwrite, relativePath);

        Map<String, String> result = new HashMap<>();
        result.put("url", relativePath);
        result.put("relativePath", relativePath);
        result.put("filename", newFilename);
        result.put("imageId", md5);
        result.put("originalFilename", originalFilename);
        result.put("size", String.valueOf(processed.getBytes().length));
        result.put("width", String.valueOf(processed.getWidth()));
        result.put("height", String.valueOf(processed.getHeight()));
        result.put("format", outputExt);
        result.put("compressionRatio", ratioStr);
        return result;
    }

    /**
     * 上传权限校验
     * - avatar 模块允许未登录（微信首次授权登录时头像先于登录态上传）；
     * - 其余业务模块（product/category/shop/other）必须为已登录管理员。
     */
    private void requireUploadPermission(String module, String bizType) {
        String cleanModule = resolveCleanModule(module, bizType);
        if ("avatar".equals(cleanModule)) {
            return;
        }
        if (SecurityContextUtil.getUserId() == null) {
            throw new BusinessException(401, "请先登录");
        }
        if (!SecurityContextUtil.isAdmin()) {
            throw new BusinessException(403, "无权上传业务图片");
        }
    }

    /**
     * 解析实际物理存储根路径
     */
    private String resolveBaseUploadPath() {
        String baseUploadPath = fileConfigProperties.getBasePath();
        if (StringUtils.hasText(baseUploadPath)) {
            return baseUploadPath.trim();
        }
        return System.getProperty("user.dir") + File.separator + "uploads";
    }

    /**
     * 解析业务模块名
     */
    private String resolveCleanModule(String module, String bizType) {
        String type = StringUtils.hasText(module) ? module : bizType;
        if (!StringUtils.hasText(type)) {
            return "product";
        }
        String clean = type.trim().toLowerCase().replaceAll("[^a-z0-9_-]", "");
        if ("avatar".equals(clean) || "product".equals(clean) || "category".equals(clean)
                || "shop".equals(clean)) {
            return clean;
        }
        return "other";
    }

    /**
     * 构建无日期的业务存储子目录：
     * - 头像 avatar: avatar/{userId}
     * - 商品 product: product
     * - 其他模块: {cleanModule}
     */
    private String resolveSubDir(String cleanModule, String paramUserId, String headerUserId) {
        if ("avatar".equals(cleanModule)) {
            String userId = resolveUserId(paramUserId, headerUserId);
            return "avatar" + File.separator + userId;
        }
        return cleanModule;
    }

    /**
     * 多途径提取用户 ID（用于头像目录分类）
     */
    private String resolveUserId(String paramUserId, String headerUserId) {
        // 1. 已登录用户优先，避免客户端伪造 userId 把文件写进他人头像目录
        String loginUserId = SecurityContextUtil.getUserId();
        if (StringUtils.hasText(loginUserId)) {
            return loginUserId;
        }
        // 2. 传参
        String id = sanitizeSegment(paramUserId);
        if (StringUtils.hasText(id)) {
            return id;
        }
        // 3. 请求头 X-User-Id
        id = sanitizeSegment(headerUserId);
        if (StringUtils.hasText(id)) {
            return id;
        }
        // 4. 兜底
        return "default";
    }

    /**
     * 过滤非法字符，防止路径穿越
     */
    private String sanitizeSegment(String segment) {
        if (!StringUtils.hasText(segment)) {
            return "";
        }
        return segment.replaceAll("[^a-zA-Z0-9_-]", "").trim();
    }

    private boolean isAllowed(String ext) {
        if (!StringUtils.hasText(ext)) {
            return false;
        }
        for (String allow : ALLOWED_EXT) {
            if (allow.equalsIgnoreCase(ext)) {
                return true;
            }
        }
        return false;
    }

    private String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
