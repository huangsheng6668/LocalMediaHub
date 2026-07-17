// Shared DOM element references. Module scripts are deferred, so the DOM is
// ready when this evaluates.
export const elements = {
    menuDashboard: document.getElementById('menu-dashboard'),
    menuBrowser: document.getElementById('menu-browser'),
    menuTags: document.getElementById('menu-tags'),
    menuSettings: document.getElementById('menu-settings'),

    // Round 16 C1: Responsive sidebar drawer
    hamburgerBtn: document.getElementById('btn-hamburger'),
    sidebarBackdrop: document.getElementById('sidebar-backdrop'),
    sidebar: document.querySelector('.sidebar'),


    pageTitle: document.getElementById('page-title'),
    btnTriggerScan: document.getElementById('btn-trigger-scan'),
    toastContainer: document.getElementById('toast-container'),

    // Views
    viewDashboard: document.getElementById('view-dashboard'),
    viewBrowser: document.getElementById('view-browser'),
    viewTags: document.getElementById('view-tags'),
    viewSettings: document.getElementById('view-settings'),
    // Reader view (Task 15): hosts textReader render output and is reused as
    // the bookshelf container in Task 16 — single shared off-menu surface.
    viewReader: document.getElementById('view-reader'),

    // Stats
    statRoots: document.getElementById('stat-roots'),
    statVideos: document.getElementById('stat-videos'),
    statImages: document.getElementById('stat-images'),
    statTags: document.getElementById('stat-tags'),
    dashboardRecent: document.getElementById('dashboard-recent'),
    infoIp: document.getElementById('info-ip'),
    infoHost: document.getElementById('info-host'),
    infoScanRoots: document.getElementById('info-scan-roots'),

    // Browser
    browserBreadcrumbs: document.getElementById('browser-breadcrumbs'),
    browserSearchInput: document.getElementById('browser-search-input'),
    btnBrowserSearch: document.getElementById('btn-browser-search'),
    browserList: document.getElementById('browser-list'),

    // Tag creator
    tagNameInput: document.getElementById('tag-name'),
    colorDots: document.querySelectorAll('.color-dot'),
    btnCreateTag: document.getElementById('btn-create-tag'),
    tagsManagerList: document.getElementById('tags-manager-list'),

    // Settings inputs
    settingsRoots: document.getElementById('settings-roots'),
    settingsVideoExts: document.getElementById('settings-video-exts'),
    settingsImageExts: document.getElementById('settings-image-exts'),
    settingsAllowedRoots: document.getElementById('settings-allowed-roots'),
    settingsEnableDelete: document.getElementById('settings-enable-delete'),
    settingsThumbMax: document.getElementById('settings-thumb-max'),
    btnSaveSettings: document.getElementById('btn-save-settings'),

    // Modals
    modalVideoPlayer: document.getElementById('modal-video-player'),
    videoPlayer: document.getElementById('html5-video-player'),
    videoModalTitle: document.getElementById('video-modal-title'),
    btnVideoTranscode: document.getElementById('btn-video-transcode'),
    btnVideoDelete: document.getElementById('btn-video-delete'),
    btnCloseVideoModal: document.getElementById('btn-close-video-modal'),

    // Custom controls
    videoControlsOverlay: document.getElementById('video-controls-overlay'),
    videoProgress: document.getElementById('video-progress'),
    btnVideoPlayPause: document.getElementById('btn-video-play-pause'),
    videoTimeDisplay: document.getElementById('video-time-display'),
    btnVideoMute: document.getElementById('btn-video-mute'),
    videoVolume: document.getElementById('video-volume'),
    btnVideoFullscreen: document.getElementById('btn-video-fullscreen'),

    modalImagePreview: document.getElementById('modal-image-preview'),
    lightboxImg: document.getElementById('lightbox-img'),
    lightboxCaption: document.getElementById('lightbox-caption'),
    btnImagePrev: document.getElementById('btn-image-prev'),
    btnImageNext: document.getElementById('btn-image-next'),
    btnCloseImageModal: document.getElementById('btn-close-image-modal'),
    btnImageModeToggle: document.getElementById('btn-image-mode-toggle'),
    lightboxSingleView: document.getElementById('lightbox-single-view'),
    lightboxStitchView: document.getElementById('lightbox-stitch-view'),

    modalFileTags: document.getElementById('modal-file-tags'),
    tagModalFilePath: document.getElementById('tag-modal-file-path'),
    tagSelectorCheckboxes: document.getElementById('tag-selector-checkboxes'),
    btnCloseFileTagsModal: document.getElementById('btn-close-file-tags-modal'),

    // Auth modal (Round 28 Task 12)
    authModal: document.getElementById('auth-modal'),
    authTokenInput: document.getElementById('auth-token-input'),
    authSaveBtn: document.getElementById('auth-save-btn'),
    authCancelBtn: document.getElementById('auth-cancel-btn')
};
