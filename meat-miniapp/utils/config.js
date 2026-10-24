/**
 * 全局配置环境项
 */
const ENV = 'prod'; // 'dev' | 'prod'

const CONFIG = {
  dev: {
    // 后端接口地址
    baseUrl: 'http://127.0.0.1:8083/meat',
    // 前端独立配置文件服务器地址（后端无需配置，前端动态拼接相对路径）
    fileBaseServer: 'http://localhost:8081/meat_file'
  },
  prod: {
    baseUrl: 'https://2uu4401930iw.vicp.fun/meat',
    fileBaseServer: 'https://2uu4401930iw.vicp.fun/meat_file'
  }
};

module.exports = {
  BASE_URL: CONFIG[ENV].baseUrl,
  FILE_BASE_SERVER: CONFIG[ENV].fileBaseServer,

  /**
   * 将后端或数据库存储的相对路径统一转换为可直接渲染的完整图片 URL
   * 参考 order 规范：
   * 1. 完整 URL (http:// 或 https://) 原样保留；
   * 2. 纯相对路径（如 /product/20261017/xxx.jpg）自动拼接 file.base-server 地址。
   *
   * @param {string} path 相对路径或绝对URL
   * @returns {string} 完整的可访问图片地址
   */
  formatImageUrl(path) {
    if (!path) return '';
    const p = String(path).trim();
    if (!p) return '';
    // 如果已经是 http/https 绝对地址，直接返回
    if (/^https?:\/\//i.test(p)) {
      return p;
    }
    const base = (CONFIG[ENV].fileBaseServer || '').replace(/\/+$/, '');
    if (!base) {
      return p.startsWith('/') ? p : '/' + p;
    }
    return base + (p.startsWith('/') ? p : '/' + p);
  },

  /**
   * 将上传接口返回的地址统一转换为「纯相对路径」以便落库保存
   * 确保存入数据库不包含 file.base-server，支持传入 relativePath 或 url
   *
   * @param {string} url 完整URL
   * @param {string} relativePath 相对路径（可选）
   * @returns {string} 纯相对路径（如 /product/20261017/xxx.jpg）
   */
  toRelativePath(url, relativePath) {
    if (relativePath) {
      const rp = String(relativePath).trim().replace(/\\/g, '/');
      if (rp) return rp.startsWith('/') ? rp : '/' + rp;
    }
    if (!url) return '';
    let u = String(url).trim().replace(/\\/g, '/');
    if (!u) return '';

    const base = (CONFIG[ENV].fileBaseServer || '').replace(/\/+$/, '');
    if (base && u.startsWith(base)) {
      u = u.slice(base.length);
      return u.startsWith('/') ? u : '/' + u;
    }

    // 检查是否包含本系统内部模块目录或前缀
    const internalKeywords = [
      '/order_file/', '/meat_file/', '/meta_file/', '/api/uploads/', '/uploads/',
      '/product/', '/avatar/', '/category/', '/shop/'
    ];
    const lower = u.toLowerCase();
    let matchIdx = -1;
    for (let i = 0; i < internalKeywords.length; i++) {
      const idx = lower.indexOf(internalKeywords[i]);
      if (idx >= 0 && (matchIdx === -1 || idx < matchIdx)) {
        matchIdx = idx;
      }
    }

    if (matchIdx >= 0) {
      let sub = u.slice(matchIdx);
      if (sub.startsWith('/order_file/')) sub = sub.slice('/order_file'.length);
      else if (sub.startsWith('/meat_file/')) sub = sub.slice('/meat_file'.length);
      else if (sub.startsWith('/meta_file/')) sub = sub.slice('/meta_file'.length);
      else if (sub.startsWith('/api/uploads/')) sub = sub.slice('/api/uploads'.length);
      else if (sub.startsWith('/uploads/')) sub = sub.slice('/uploads'.length);
      return sub.startsWith('/') ? sub : '/' + sub;
    }

    // 若包含 localhost / 127.0.0.1 等本地开发服务器标识
    if (/^https?:\/\/(localhost|127\.0\.0\.1)/i.test(u)) {
      const slashIdx = u.indexOf('://');
      const pathIdx = u.indexOf('/', slashIdx + 3);
      if (pathIdx >= 0) {
        return u.slice(pathIdx);
      }
    }

    // 如果是第三方绝对地址（如微信官方头像 qlogo.cn 或 Unsplash 等外部图床），原样保留
    if (/^https?:\/\//i.test(u)) {
      return u;
    }

    return u.startsWith('/') ? u : '/' + u;
  }
};

