// Keeps a running conversion's bar on the Cleanup page current, every few seconds, without
// reloading the page. Without this script the bar shows where things stood when the page loaded.
(() => {
    const box = document.querySelector("[data-progress]");
    if (!box) return;
    const bar = box.querySelector("progress");
    const text = box.querySelector(".progress-text");

    const poll = async () => {
        let job;
        try {
            const response = await fetch(box.dataset.progress, { headers: { Accept: "application/json" } });
            job = await response.json();
        } catch (e) {
            // The server restarting, or a lost login: try again a little later.
            setTimeout(poll, 10000);
            return;
        }
        bar.max = Math.max(job.total, 1);
        bar.value = job.done;
        text.textContent = job.text;
        if (!job.finished) setTimeout(poll, 3000);
    };
    setTimeout(poll, 3000);
})();
