package com.xcs.wx.service.impl;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.HexUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpUtil;
import com.xcs.wx.repository.HardLinkImageAttributeRepository;
import com.xcs.wx.service.ImageService;
import com.xcs.wx.service.UserService;
import com.xcs.wx.util.DirUtil;
import com.xcs.wx.util.ImgDecoderUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 图片服务实现
 *
 * @author xcs
 * @date 2024年1月18日22:06:46
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageServiceImpl implements ImageService {

    private final HardLinkImageAttributeRepository hardLinkImageAttributeRepository;
    private final UserService userService;

    /**
     * 校验 URL 是否为 SSRF 攻击向量（内网地址、localhost、非 http/https）
     */
    private static boolean isSafeUrl(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                return false;
            }
            String host = uri.getHost();
            if (host == null) {
                return false;
            }
            // 解析并检查 host 是否为内网地址
            InetAddress addr = InetAddress.getByName(host);
            if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()
                    || addr.isSiteLocalAddress() || addr.isAnyLocalAddress()) {
                return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 校验本地路径是否包含遍历攻击（..、绝对路径、盘符）
     */
    private static boolean isSafeLocalPath(String localPath) {
        if (StrUtil.isBlank(localPath)) {
            return false;
        }
        // 禁止路径遍历
        if (localPath.contains("..")) {
            return false;
        }
        // 禁止以 / 开头的 Unix 绝对路径
        if (localPath.startsWith("/")) {
            return false;
        }
        // 禁止 Windows 盘符开头 (C:\、D:/ 等)
        if (localPath.matches("^[a-zA-Z]:[\\\\/].*")) {
            return false;
        }
        return true;
    }

    @Override
    public ResponseEntity<Resource> downloadImgMd5(String md5) {
        try {
            // 查询数据库
            String imgUrl = hardLinkImageAttributeRepository.queryHardLinkImage(HexUtil.decodeHex(md5));
            // 查询结果为空，返回404
            if (StrUtil.isBlank(imgUrl)) {
                return ResponseEntity.notFound().build();
            }
            // 获取用户信息
            String wxId = userService.currentUser();
            // 获得文件目录
            String filePath = DirUtil.getDir(userService.getBasePath(wxId), wxId, imgUrl);
            // 检查文件是否存在
            if (!FileUtil.exist(filePath)) {
                return ResponseEntity.notFound().build();
            }
            // 获取图片文件夹地址
            String outPath = DirUtil.getImgDir(wxId);
            // 解密并返回
            String imgPath = ImgDecoderUtil.decodeDat(filePath, outPath);
            // 如果图片地址为空
            if (imgPath == null) {
                return ResponseEntity.notFound().build();
            }
            // 返回图片
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG)
                    .body(new InputStreamResource(Files.newInputStream(Paths.get(imgPath))));
        } catch (Exception e) {
            log.error("downloadImgMd5 error", e);
        }
        // 默认返回404
        return ResponseEntity.notFound().build();
    }

    @Override
    public ResponseEntity<Resource> downloadImg(String path) {
        // SSRF 防护：校验 URL scheme 和目标 host 是否为内网
        if (!isSafeUrl(path)) {
            log.warn("SSRF block: unsafe URL rejected: {}", path);
            return ResponseEntity.badRequest().build();
        }
        // 获取用户信息
        String wxId = userService.currentUser();
        // 返回默认图片
        String destPath = DirUtil.getImgDirWithName(wxId, IdUtil.fastSimpleUUID() + ".gif");
        try {
            // 下载图片
            HttpUtil.downloadFile(path, destPath);
            // 返回图片
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG)
                    .body(new InputStreamResource(Files.newInputStream(Paths.get(destPath))));
        } catch (Exception ignore) {
            // 忽略异常
        }
        // 默认返回404
        return ResponseEntity.notFound().build();
    }

    @Override
    public ResponseEntity<Resource> downloadImgFormLocal(String localPath) {
        // 路径遍历防护：禁止 ..、绝对路径、Windows 盘符
        if (!isSafeLocalPath(localPath)) {
            log.warn("Path traversal block: unsafe localPath rejected: {}", localPath);
            return ResponseEntity.badRequest().build();
        }
        try {
            // 获取用户信息
            String wxId = userService.currentUser();
            // 获得文件目录
            String filePath = DirUtil.getDir(userService.getBasePath(wxId), wxId, localPath);
            // 路径二次归一化后再检查，防止 DirUtil 拼接后仍有遍历
            Path normalized = Paths.get(filePath).normalize();
            Path baseDir = Paths.get(DirUtil.getDir(userService.getBasePath(wxId), wxId)).toAbsolutePath();
            if (!normalized.toAbsolutePath().startsWith(baseDir)) {
                log.warn("Path traversal block: resolved path escapes base dir: {}", filePath);
                return ResponseEntity.badRequest().build();
            }
            // 检查文件是否存在，返回404
            if (!FileUtil.exist(filePath)) {
                return ResponseEntity.notFound().build();
            }
            // 获取图片文件夹地址
            String outPath = DirUtil.getImgDir(wxId);
            // 检查文件是否存在
            if (!FileUtil.exist(outPath)) {
                FileUtil.mkdir(outPath);
            }
            // 解密并返回
            String imgPath = ImgDecoderUtil.decodeDat(filePath, outPath);
            // 如果图片地址为空
            if (imgPath == null) {
                return ResponseEntity.notFound().build();
            }
            // 返回图片
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG)
                    .body(new InputStreamResource(Files.newInputStream(Paths.get(imgPath))));
        } catch (Exception e) {
            log.error("downloadImgFormLocal error", e);
        }
        // 默认返回404
        return ResponseEntity.notFound().build();
    }
}
