// State Management
export const state = {
    activeTab: 'dashboard',
    folders: [], // Config roots
    allowedRoots: [], // System allowed roots
    videoExts: [],
    imageExts: [],
    textExts: [],
    
    // Browser variables
    currentPath: '',
    pathHistory: [],
    currentFiles: [], // Current browser view media files
    currentFolders: [], // Current browser view directories
    isSystemBrowse: false,
    sortField: (typeof localStorage !== 'undefined' && localStorage.getItem('lmh_browser_sort_field')) || 'name', // 'name' | 'modified_time' | 'size' | 'extension'
    sortOrder: (typeof localStorage !== 'undefined' && localStorage.getItem('lmh_browser_sort_order')) || 'asc',   // 'asc' | 'desc'
    favoritesOnly: false,
    statusFilter: null, // null | 'unread' | 'reading' | 'finished'
    
    // Lightbox variables
    lightboxFiles: [],
    lightboxIndex: -1,
    lightboxStitchMode: typeof localStorage !== 'undefined' && localStorage.getItem('lightboxStitchMode') === 'true',

    // Dashboard recent media (backing array for index-based click delegation)
    dashboardRecentFiles: [],
    
    // Transcode flag for video player
    useTranscode: false,
    vcodecMode: 'copy', // 'copy' (Remux) or 'libx264' (Full transcode)
    playingFile: null,
    videoDuration: 0,
    transcodeStartOffset: 0,
    isDraggingProgress: false,

    apiBase: '',

    // Populated by settings.js loadConfig() from GET /api/v1/admin/config.
    // Declared here so the contract is visible and the pre-load defaults are
    // deliberate rather than accidental `undefined`.
    //   enableDelete defaults to false: delete buttons must stay hidden until
    //   the server has confirmed deletion is allowed, so a slow or failed config
    //   fetch can never expose a destructive control.
    //   thumbMax mirrors the `|| 300` fallback used when the server omits it.
    enableDelete: false,
    thumbMax: 300,

    // Auth token for API authentication
    authToken: (typeof sessionStorage !== 'undefined' && sessionStorage.getItem('lmh_auth_token')) || ''
};

// Auth token setter with sessionStorage sync
export function setAuthToken(token) {
    state.authToken = token;
    if (token) {
        sessionStorage.setItem('lmh_auth_token', token);
    } else {
        sessionStorage.removeItem('lmh_auth_token');
    }
}
