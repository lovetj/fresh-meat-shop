package com.freshmeat;

import com.freshmeat.common.Result;
import com.freshmeat.config.FileConfigProperties;
import com.freshmeat.controller.FileUploadController;
import com.freshmeat.util.ImageCompressUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class FileUploadControllerTest {

    private FileUploadController controller;
    private FileConfigProperties properties;

    @TempDir
    Path tempDir;

    @BeforeEach
    public void setup() {
        properties = new FileConfigProperties();
        properties.setBasePath(tempDir.toAbsolutePath().toString());
        properties.setBaseServer("http://localhost:8083/meat_file");
        controller = new FileUploadController(properties);
    }

    private byte[] createTestImage(int width, int height, String format) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.WHITE);
        g.drawString("FRESH_MEAT_TEST", 20, 30);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    @Test
    public void testScaleLargeImageTo750WidthAndCompress() throws IOException {
        // 创建 1500x1000 宽图
        byte[] imageBytes = createTestImage(1500, 1000, "jpg");
        MockMultipartFile file = new MockMultipartFile("file", "large_meat.jpg", "image/jpeg", imageBytes);

        Result<Map<String, String>> res = controller.upload(
                file, "product", null, null, null, null);

        assertNotNull(res);
        assertEquals(200, res.getCode());
        Map<String, String> data = res.getData();
        assertNotNull(data);
        assertEquals("750", data.get("width"));
        assertEquals("500", data.get("height"));

        // 验证路径无日期，格式为 /product/{md5}.xxx
        String relPath = data.get("relativePath");
        assertTrue(relPath.startsWith("/product/"));
        assertFalse(relPath.matches(".*/\\d{8}/.*"), "路径中不应包含日期格式 yyyyMMdd");

        String md5 = data.get("imageId");
        assertNotNull(md5);
        assertEquals(32, md5.length());
        assertTrue(relPath.endsWith(md5 + "." + data.get("format")));

        // 验证磁盘落盘
        File diskFile = tempDir.resolve(relPath.substring(1)).toFile();
        assertTrue(diskFile.exists());
    }

    @Test
    public void testKeepSmallImageOriginalDimensions() throws IOException {
        // 创建 300x200 小图
        byte[] imageBytes = createTestImage(300, 200, "jpg");
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", imageBytes);

        Result<Map<String, String>> res = controller.upload(
                file, "avatar", null, "10086", null, null);

        assertNotNull(res);
        assertEquals(200, res.getCode());
        Map<String, String> data = res.getData();
        assertEquals("300", data.get("width"));
        assertEquals("200", data.get("height"));

        // 验证头像按用户ID拼接：/avatar/10086/{md5}.xxx
        String relPath = data.get("relativePath");
        assertTrue(relPath.startsWith("/avatar/10086/"), "头像路径应使用 userId 拼接");
        assertFalse(relPath.matches(".*/\\d{8}/.*"), "路径中不应包含日期格式 yyyyMMdd");
    }

    @Test
    public void testSameImageOverwrite() throws IOException {
        // 相同内容图片多次上传，MD5 应该完全相同，且覆盖同名文件
        byte[] imageBytes = createTestImage(400, 400, "jpg");
        MockMultipartFile file1 = new MockMultipartFile("file", "meat1.jpg", "image/jpeg", imageBytes);
        MockMultipartFile file2 = new MockMultipartFile("file", "meat2.jpg", "image/jpeg", imageBytes);

        Result<Map<String, String>> res1 = controller.upload(file1, "product", null, null, null, null);
        Result<Map<String, String>> res2 = controller.upload(file2, "product", null, null, null, null);

        assertEquals(200, res1.getCode());
        assertEquals(200, res2.getCode());

        String path1 = res1.getData().get("relativePath");
        String path2 = res2.getData().get("relativePath");
        assertEquals(path1, path2, "相同内容的图片上传应产生相同的相对路径和文件名（基于MD5）");

        File diskFile = tempDir.resolve(path1.substring(1)).toFile();
        assertTrue(diskFile.exists());
    }

    @Test
    public void testDeleteFile() throws IOException {
        byte[] imageBytes = createTestImage(500, 300, "jpg");
        MockMultipartFile file = new MockMultipartFile("file", "to_delete.jpg", "image/jpeg", imageBytes);

        Result<Map<String, String>> res = controller.upload(file, "product", null, null, null, null);
        String relPath = res.getData().get("relativePath");

        File diskFile = tempDir.resolve(relPath.substring(1)).toFile();
        assertTrue(diskFile.exists());

        Result<Void> delRes = controller.delete(relPath);
        assertEquals(200, delRes.getCode());
        assertFalse(diskFile.exists(), "文件应被物理删除");
    }
}
