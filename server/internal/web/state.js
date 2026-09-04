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
