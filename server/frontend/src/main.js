import { App } from './App.js';

window.exports = {};
window.wails = { runtime: {} };

document.addEventListener('DOMContentLoaded', () => {
    const app = document.getElementById('app');
    app.textContent = 'LocalMediaHub v0.2.0';
});
