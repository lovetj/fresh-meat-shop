const config = require('./config');

/**
 * 通用常用工具函数
 */
function formatTime(date) {
  if (!date) return '';
  const d = new Date(date);
  const year = d.getFullYear();
  const month = (d.getMonth() + 1).toString().padStart(2, '0');
  const day = d.getDate().toString().padStart(2, '0');
  const hour = d.getHours().toString().padStart(2, '0');
  const minute = d.getMinutes().toString().padStart(2, '0');
  return `${year}-${month}-${day} ${hour}:${minute}`;
}

function showToast(title, icon = 'none') {
  wx.showToast({
    title: title || '',
    icon,
    duration: 2000
  });
}

function showLoading(title = '加载中...') {
  wx.showLoading({
    title,
    mask: true
  });
}

function hideLoading() {
  wx.hideLoading();
}

/**
 * 图片公共转换方法：统一拼接 file.base-server
 */
function formatImageUrl(path) {
  return config.formatImageUrl(path);
}

/**
 * 转换为相对路径方法：入库时剔除 baseServer
 */
function toRelativePath(url, relativePath) {
  return config.toRelativePath(url, relativePath);
}

// 肉铺推荐加工方式与具体规格模板
const DEFAULT_PROCESS_SPECS = [
  {
    name: '整块',
    options: ['原样整块', '去皮整块']
  },
  {
    name: '切片',
    options: ['0.2cm薄切(涮烤/爆炒)', '0.5cm厚切(家常炒肉)']
  },
  {
    name: '切块',
    options: ['煲汤大块(约4-5cm)', '红烧小块(约2-3cm)', '小肉丁(约1cm)']
  },
  {
    name: '绞肉',
    options: ['细馅(包子/饺子/馄饨)', '粗肉末(炸酱/肉丸)']
  }
];

/**
 * 统一规范化加工方式与具体规格数据结构：
 * 返回结构: [{ name: '切片', options: ['0.2cm薄切', '0.5cm厚切'] }]
 */
function normalizeSpecsList(specsJson, fallbackToDefault = false) {
  if (!specsJson) {
    return fallbackToDefault ? JSON.parse(JSON.stringify(DEFAULT_PROCESS_SPECS)) : [];
  }

  let parsed = null;
  try {
    parsed = typeof specsJson === 'string' ? JSON.parse(specsJson) : specsJson;
  } catch (e) {
    return fallbackToDefault ? JSON.parse(JSON.stringify(DEFAULT_PROCESS_SPECS)) : [];
  }

  if (!Array.isArray(parsed) || parsed.length === 0) {
    return fallbackToDefault ? JSON.parse(JSON.stringify(DEFAULT_PROCESS_SPECS)) : [];
  }

  // 1. 兼容历史包含 { name: "加工方式", options: [...] } 结构
  const processGroup = parsed.find(item => item && (item.name === '加工方式' || item.name === '切法'));
  if (processGroup && Array.isArray(processGroup.options) && processGroup.options.length > 0) {
    const otherGroups = parsed.filter(item => item !== processGroup && Array.isArray(item.options) && item.options.length > 0);
    const defaultChildOpts = otherGroups.length > 0 ? otherGroups[0].options : [];

    return processGroup.options.map(methodName => {
      return {
        name: methodName,
        options: defaultChildOpts.length > 0 ? [...defaultChildOpts] : [methodName]
      };
    });
  }

  // 2. 正常数组转换
  const result = [];
  for (const item of parsed) {
    if (typeof item === 'string' && item.trim()) {
      result.push({
        name: item.trim(),
        options: [item.trim()]
      });
    } else if (item && typeof item === 'object' && item.name) {
      const name = String(item.name).trim();
      if (!name) continue;
      let opts = [];
      if (Array.isArray(item.options) && item.options.length > 0) {
        opts = item.options.map(o => String(o).trim()).filter(Boolean);
      }
      if (opts.length === 0) {
        opts = [name];
      }
      result.push({
        name,
        options: opts
      });
    }
  }

  if (result.length === 0 && fallbackToDefault) {
    return JSON.parse(JSON.stringify(DEFAULT_PROCESS_SPECS));
  }
  return result;
}

module.exports = {
  formatTime,
  showToast,
  showLoading,
  hideLoading,
  formatImageUrl,
  toRelativePath,
  DEFAULT_PROCESS_SPECS,
  normalizeSpecsList
};

