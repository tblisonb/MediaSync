// The image viewer: ← and → step through the folder and Escape goes back to it, by following the
// page's own rel="prev", rel="next" and rel="up" links.
document.addEventListener("keydown", (event) => {
    if (event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return;
    if (event.target.closest("input, textarea, select, video")) return;
    const rel = { ArrowLeft: "prev", ArrowRight: "next", Escape: "up" }[event.key];
    const link = rel && document.querySelector(`a[rel="${rel}"]`);
    if (link) location.href = link.href;
});
