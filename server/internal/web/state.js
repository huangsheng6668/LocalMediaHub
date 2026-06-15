// State Management
export const state = {
    activeTab: 'dashboard',
    folders: [], // Config roots
    allowedRoots: [], // System allowed roots
    videoExts: [],
    imageExts: [],
    
    // Browser variables
    currentPath: '',
    pathHistory: [],
    currentFiles: [], // Current browser view media files
    currentFolders: [], // Current browser view directories
    isSystemBrowse: false,
    
    // Tags variables
    tags: [],
    fileTagsMap: {},
    
    // Lightbox variables
    lightboxFiles: [],
    lightboxIndex: -1,
    lightboxStitchMode: localStorage.getItem('lightboxStitchMode') === 'true',
    
    // Selected file for tag mapping
    taggingFile: null,
    
    // Transcode flag for video player
    useTranscode: false,
    vcodecMode: 'copy', // 'copy' (Remux) or 'libx264' (Full transcode)
    playingFile: null,
    videoDuration: 0,
    transcodeStartOffset: 0,
    isDraggingProgress: false,
    
    apiBase: ''
};
