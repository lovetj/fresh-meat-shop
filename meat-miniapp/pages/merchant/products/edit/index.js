// pages/merchant/products/edit/index.js
const request = require('../../../../utils/request');
const auth = require('../../../../utils/auth');
const { formatImageUrl, toRelativePath, DEFAULT_PROCESS_SPECS, normalizeSpecsList } = require('../../../../utils/util');

Page({
  data: {
    isEdit: false,
    id: null,
    shopId: '',
    shopList: [],
    shopIndex: -1,
    categories: [],
    categoryIndex: -1,
    initialCategoryId: '',
    product: {
      shopId: '',
      categoryId: null,
      name: '',
      coverImage: '',
      displayCoverImage: '',
      price: '',
      originalPrice: '',
      unit: '斤',
      stock: 999,
      sortOrder: 0,
      summary: '',
      description: '',
      isOnSale: 1
    },
    // 加工方式与具体规格列表：[{ name: '切片', options: ['薄片', '厚片'] }]
    specsList: JSON.parse(JSON.stringify(DEFAULT_PROCESS_SPECS)),
    submitting: false
  },

  onLoad(options) {
    if (!auth.isAdmin()) {
      wx.showToast({ title: '无掌柜权限', icon: 'none' });
      setTimeout(() => {
        wx.switchTab({ url: '/pages/mine/mine' });
      }, 1000);
      return;
    }

    const id = options && options.id;
    const initialShopId = (options && options.shopId) || wx.getStorageSync('adminShopId') || '';
    const initialCategoryId = (options && options.categoryId) || '';

    if (id) {
      this.setData({ isEdit: true, id: id, shopId: initialShopId, initialCategoryId });
      wx.setNavigationBarTitle({ title: '编辑商品' });
    } else {
      this.setData({ shopId: initialShopId, initialCategoryId });
      wx.setNavigationBarTitle({ title: '新增商品' });
    }

    this.loadShops(initialShopId).then(() => {
      if (id) {
        this.loadDetail(id);
      } else {
        this.loadCategories(this.data.shopId, initialCategoryId);
      }
    });
  },

  onShow() {
    // 若从分类管理页新增分类后返回，重新刷新当前店铺分类
    if (this.data.shopId && !this.data.id) {
      this.loadCategories(this.data.shopId, this.data.product.categoryId || this.data.initialCategoryId);
    }
  },

  // 获取管理员管辖的商铺列表
  loadShops(preferShopId) {
    return request.get('/shop/admin/my-shops', {}, { silent: true })
      .then(res => {
        const list = Array.isArray(res) ? res : [];
        let shopIdx = -1;
        const targetId = preferShopId || this.data.shopId || wx.getStorageSync('adminShopId');
        if (targetId) {
          shopIdx = list.findIndex(s => s.id === targetId);
        }
        if (shopIdx < 0 && list.length > 0) {
          shopIdx = 0;
        }

        const selectedShopId = shopIdx >= 0 ? list[shopIdx].id : '';
        this.setData({
          shopList: list,
          shopIndex: shopIdx,
          shopId: selectedShopId,
          'product.shopId': selectedShopId
        });
      })
      .catch(err => {
        console.error('加载店铺失败', err);
      });
  },

  onShopChange(e) {
    const idx = parseInt(e.detail.value, 10);
    const shop = this.data.shopList[idx];
    if (shop) {
      this.setData({
        shopIndex: idx,
        shopId: shop.id,
        'product.shopId': shop.id,
        'product.categoryId': null,
        categoryIndex: -1
      }, () => {
        this.loadCategories(shop.id);
      });
    }
  },

  // 获取指定商铺的分类列表（管理端包含所有分类并按权重降序）
  loadCategories(shopId, defaultCategoryId) {
    const targetShopId = shopId || this.data.shopId;
    const params = {};
    if (targetShopId) {
      params.shopId = targetShopId;
    }
    return request.get('/category/admin/list', params, { silent: true })
      .then(res => {
        const list = Array.isArray(res) ? res : [];
        list.sort((a, b) => (b.sortOrder || 0) - (a.sortOrder || 0));

        let catIdx = -1;
        const targetCatId = defaultCategoryId || this.data.product.categoryId;
        if (targetCatId) {
          catIdx = list.findIndex(c => String(c.id) === String(targetCatId));
        }

        const chosenCatId = catIdx >= 0 ? list[catIdx].id : null;

        this.setData({
          categories: list,
          categoryIndex: catIdx,
          'product.categoryId': chosenCatId
        });
      })
      .catch(err => {
        console.error('加载分类失败', err);
      });
  },

  // 跳转分类管理页
  goToManageCategory() {
    const shopId = this.data.shopId || '';
    wx.navigateTo({
      url: `/pages/merchant/category/index?shopId=${shopId}`
    });
  },

  // 加载商品详情回显
  loadDetail(id) {
    wx.showLoading({ title: '加载中...' });
    request.get(`/product/detail/${id}`)
      .then(res => {
        const specs = normalizeSpecsList(res.specsJson, true);

        const effectiveShopId = res.shopId || this.data.shopId;
        let shopIdx = this.data.shopIndex;
        if (effectiveShopId && this.data.shopList.length > 0) {
          const found = this.data.shopList.findIndex(s => s.id === effectiveShopId);
          if (found >= 0) shopIdx = found;
        }

        // 加载商品所属店铺分类并回显索引
        this.loadCategories(effectiveShopId, res.categoryId).then(() => {
          const catIdx = this.data.categories.findIndex(c => String(c.id) === String(res.categoryId));
          this.setData({
            categoryIndex: catIdx,
            'product.categoryId': res.categoryId
          });
        });

        this.setData({
          product: {
            id: res.id,
            shopId: effectiveShopId,
            categoryId: res.categoryId,
            name: res.name || '',
            coverImage: toRelativePath(res.coverImage),
            displayCoverImage: formatImageUrl(res.coverImage),
            price: res.price,
            originalPrice: res.originalPrice || '',
            unit: res.unit || '斤',
            stock: res.stock !== undefined ? res.stock : 999,
            sortOrder: res.sortOrder || 0,
            summary: res.summary || '',
            description: res.description || '',
            isOnSale: res.isOnSale !== undefined ? res.isOnSale : 1
          },
          shopIndex: shopIdx,
          shopId: effectiveShopId,
          specsList: specs
        });
      })
      .catch(err => {
        wx.showToast({ title: err.message || '获取商品失败', icon: 'none' });
      })
      .finally(() => {
        wx.hideLoading();
      });
  },

  onCategoryChange(e) {
    const idx = parseInt(e.detail.value, 10);
    const cat = this.data.categories[idx];
    this.setData({
      categoryIndex: idx,
      'product.categoryId': cat ? cat.id : null
    });
  },

  // 选择图片来源：拍照 / 相册
  chooseAndUploadImage() {
    wx.showActionSheet({
      itemList: ['拍照上传', '从相册选择'],
      success: (res) => {
        const sourceType = res.tapIndex === 0 ? 'camera' : 'album';
        this.pickAndUploadImage(sourceType);
      },
      fail: () => {
        // 用户取消选择
      }
    });
  },

  // 按指定来源选择并上传图片
  pickAndUploadImage(sourceType) {
    wx.chooseMedia({
      count: 1,
      mediaType: ['image'],
      sourceType: [sourceType],
      sizeType: ['compressed'],
      camera: 'back',
      success: (res) => {
        const tempFilePath = res.tempFiles[0].tempFilePath;
        wx.showLoading({ title: '上传图片中...' });
        request.uploadFile({
          filePath: tempFilePath,
          formData: { module: 'product' }
        })
          .then(data => {
            wx.showToast({ title: '上传成功', icon: 'success' });
            const relPath = toRelativePath(data.url, data.relativePath || data.relativeUrl);
            const dispUrl = formatImageUrl(relPath);
            this.setData({
              'product.coverImage': relPath,
              'product.displayCoverImage': dispUrl
            });
          })
          .catch(err => {
            console.error('上传图片失败', err);
          })
          .finally(() => {
            wx.hideLoading();
          });
      }
    });
  },

  previewCover() {
    const img = this.data.product.displayCoverImage || formatImageUrl(this.data.product.coverImage);
    if (img) {
      wx.previewImage({
        urls: [img]
      });
    }
  },

  removeCover() {
    this.setData({
      'product.coverImage': '',
      'product.displayCoverImage': ''
    });
  },

  // 添加加工方式
  onAddProcessMethod() {
    const presets = [
      { name: '整块', defaultOptions: ['原样整块', '去皮整块'] },
      { name: '切片', defaultOptions: ['0.2cm薄切(涮烤/爆炒)', '0.5cm厚切(家常炒肉)'] },
      { name: '切块', defaultOptions: ['煲汤大块(约4-5cm)', '红烧小块(约2-3cm)', '小肉丁(约1cm)'] },
      { name: '绞肉', defaultOptions: ['细馅(包子/饺子/馄饨)', '粗肉末(炸酱/肉丸)'] }
    ];

    // 微信限制 itemList 最多为 6 个（此处 4 个常用预设 + 1 个自定义 = 5 项）
    const actionList = presets.map(p => p.name).concat(['✍️ 自定义加工方式']);

    wx.showActionSheet({
      itemList: actionList,
      success: (res) => {
        const tapIndex = res.tapIndex;
        if (tapIndex < presets.length) {
          const selected = presets[tapIndex];
          const exists = this.data.specsList.some(item => item.name === selected.name);
          if (exists) {
            wx.showToast({ title: `「${selected.name}」已存在`, icon: 'none' });
            return;
          }
          const list = this.data.specsList.concat([{
            name: selected.name,
            options: [...selected.defaultOptions]
          }]);
          this.setData({ specsList: list });
          wx.showToast({ title: `已添加「${selected.name}」`, icon: 'success' });
        } else {
          this.promptCustomMethod();
        }
      },
      fail: (err) => {
        // 若非用户主动点击取消，降级为直接输入弹窗
        if (err && err.errMsg && !err.errMsg.includes('cancel')) {
          this.promptCustomMethod();
        }
      }
    });
  },

  promptCustomMethod() {
    wx.showModal({
      title: '添加加工方式',
      editable: true,
      placeholderText: '如：切薄片、剃骨切排、切肉丝',
      success: (modalRes) => {
        if (modalRes.confirm && modalRes.content) {
          const name = modalRes.content.trim();
          if (!name) return;
          const exists = this.data.specsList.some(item => item.name === name);
          if (exists) {
            wx.showToast({ title: `「${name}」已存在`, icon: 'none' });
            return;
          }
          const list = this.data.specsList.concat([{
            name: name,
            options: ['标准加工']
          }]);
          this.setData({ specsList: list });
          wx.showToast({ title: '添加成功', icon: 'success' });
        }
      }
    });
  },

  // 删除某种加工方式
  onRemoveProcessMethod(e) {
    const idx = e.currentTarget.dataset.index;
    const methodItem = this.data.specsList[idx];
    if (!methodItem) return;

    wx.showModal({
      title: '删除加工方式确认',
      content: `确定删除加工方式「${methodItem.name}」及其下的所有具体规格吗？`,
      confirmColor: '#FF4757',
      success: (res) => {
        if (res.confirm) {
          const list = this.data.specsList.filter((_, i) => i !== idx);
          this.setData({ specsList: list });
        }
      }
    });
  },

  // 给指定加工方式添加具体规格
  onAddOption(e) {
    const methodIndex = e.currentTarget.dataset.index;
    const methodItem = this.data.specsList[methodIndex];
    if (!methodItem) return;

    wx.showModal({
      title: `添加「${methodItem.name}」的具体规格`,
      editable: true,
      placeholderText: '如：0.2cm薄片、带皮厚切、小肉丁',
      success: (res) => {
        if (res.confirm && res.content) {
          const opt = res.content.trim();
          if (!opt) return;
          const currentOpts = methodItem.options || [];
          if (currentOpts.includes(opt)) {
            wx.showToast({ title: '规格已存在', icon: 'none' });
            return;
          }
          const list = [...this.data.specsList];
          list[methodIndex].options = currentOpts.concat([opt]);
          this.setData({ specsList: list });
        }
      }
    });
  },

  // 删除指定加工方式下的具体规格
  onRemoveOption(e) {
    const { methodIndex, optionIndex } = e.currentTarget.dataset;
    const list = [...this.data.specsList];
    const method = list[methodIndex];
    if (!method || !method.options) return;

    method.options = method.options.filter((_, i) => i !== optionIndex);
    this.setData({ specsList: list });
  },

  // 一键恢复推荐配置
  onResetDefaultSpecs() {
    wx.showModal({
      title: '恢复推荐规格',
      content: '是否重置并应用肉铺常用的推荐加工方式与具体规格配置？',
      success: (res) => {
        if (res.confirm) {
          this.setData({
            specsList: JSON.parse(JSON.stringify(DEFAULT_PROCESS_SPECS))
          });
          wx.showToast({ title: '已恢复推荐规格', icon: 'success' });
        }
      }
    });
  },

  onSaleChange(e) {
    this.setData({
      'product.isOnSale': e.detail.value ? 1 : 0
    });
  },

  // 提交保存
  onSubmitForm(e) {
    if (this.data.submitting) return;

    const values = e.detail.value;
    const { categoryId, coverImage, isOnSale } = this.data.product;

    if (!this.data.shopId) {
      wx.showToast({ title: '请选择所属门店', icon: 'none' });
      return;
    }
    if (!categoryId) {
      wx.showToast({ title: '请选择所属分类', icon: 'none' });
      return;
    }
    if (!values.name || !values.name.trim()) {
      wx.showToast({ title: '请输入商品名称', icon: 'none' });
      return;
    }
    if (!coverImage) {
      wx.showToast({ title: '请上传商品主图', icon: 'none' });
      return;
    }
    if (!values.price || isNaN(parseFloat(values.price)) || parseFloat(values.price) <= 0) {
      wx.showToast({ title: '请输入合法的销售单价', icon: 'none' });
      return;
    }

    // 校验加工方式与规格结构
    if (!this.data.specsList || this.data.specsList.length === 0) {
      wx.showToast({ title: '请至少添加一种加工方式', icon: 'none' });
      return;
    }
    for (let i = 0; i < this.data.specsList.length; i++) {
      const item = this.data.specsList[i];
      if (!item.name || !item.name.trim()) {
        wx.showToast({ title: '加工方式名称不能为空', icon: 'none' });
        return;
      }
      if (!item.options || item.options.length === 0) {
        wx.showToast({ title: `请为【${item.name}】添加具体规格`, icon: 'none' });
        return;
      }
    }

    const payload = {
      id: this.data.id || null,
      shopId: this.data.shopId,
      categoryId: categoryId,
      name: values.name.trim(),
      coverImage: coverImage,
      price: parseFloat(values.price),
      originalPrice: values.originalPrice ? parseFloat(values.originalPrice) : null,
      unit: values.unit ? values.unit.trim() : '斤',
      stock: values.stock ? parseInt(values.stock, 10) : 999,
      sortOrder: values.sortOrder ? parseInt(values.sortOrder, 10) : 0,
      summary: values.summary ? values.summary.trim() : '',
      description: values.description ? values.description.trim() : '',
      isOnSale: isOnSale,
      specsJson: JSON.stringify(this.data.specsList)
    };

    this.setData({ submitting: true });
    wx.showLoading({ title: '保存中...' });

    request.post('/product/admin/save', payload)
      .then(() => {
        wx.showToast({ title: '保存成功', icon: 'success' });
        setTimeout(() => {
          wx.navigateBack();
        }, 1200);
      })
      .catch(err => {
        wx.showToast({ title: err.message || '保存失败', icon: 'none' });
      })
      .finally(() => {
        this.setData({ submitting: false });
        wx.hideLoading();
      });
  },

  // 详情页删除商品
  onDeleteCurrentProduct() {
    const id = this.data.id;
    const name = this.data.product.name;
    if (!id) return;

    wx.showModal({
      title: '删除确认',
      content: `确定要彻底删除商品【${name || '该商品'}】吗？删除后不可恢复。`,
      confirmColor: '#FF4757',
      success: (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '删除中...' });
          request.del(`/product/admin/${id}`)
            .then(() => {
              wx.showToast({ title: '删除成功', icon: 'success' });
              setTimeout(() => {
                wx.navigateBack();
              }, 1200);
            })
            .catch(err => {
              wx.showToast({ title: err.message || '删除失败', icon: 'none' });
            })
            .finally(() => {
              wx.hideLoading();
            });
        }
      }
    });
  }
});
