// Moving previews: while the pointer is on a video's tile (or it has the focus), its thumbnail
// steps through stills from across the video, one a second. The server makes each still the first
// time it's asked for, so the next one is fetched while this one shows.
const FRAMES = 12;
const STEP = 1000;

for (const img of document.querySelectorAll("img[data-frames]")) {
    const tile = img.closest("a, label") || img;
    const thumbnail = img.getAttribute("src");
    const frames = img.dataset.frames;
    let on = false;
    let timer = null;
    let frame = 0;

    const show = () => {
        const started = Date.now();
        const url = `${frames}/${frame}`;
        const still = new Image();
        still.onload = () => {
            if (!on) return;
            img.src = url;
            frame = (frame + 1) % FRAMES;
            new Image().src = `${frames}/${frame}`;
            timer = setTimeout(show, Math.max(0, STEP - (Date.now() - started)));
        };
        still.onerror = stop;
        still.src = url;
    };
    const start = () => {
        if (on) return;
        on = true;
        frame = 0;
        show();
    };
    function stop() {
        on = false;
        clearTimeout(timer);
        img.src = thumbnail;
    }
    tile.addEventListener("mouseenter", start);
    tile.addEventListener("mouseleave", stop);
    tile.addEventListener("focus", start);
    tile.addEventListener("blur", stop);

    // Without ffmpeg on the server there's no thumbnail: say it's a video instead.
    const missing = () => {
        if (img.getAttribute("src") !== thumbnail) return;
        const placeholder = document.createElement("span");
        placeholder.className = "no-thumb";
        placeholder.textContent = "Video";
        img.replaceWith(placeholder);
        tile.removeEventListener("mouseenter", start);
        tile.removeEventListener("focus", start);
    };
    img.addEventListener("error", missing);
    if (img.complete && img.naturalWidth === 0 && img.getAttribute("loading") !== "lazy") missing();
}
