(function () {
    'use strict';

    // Khi nhấn Enter trên ô nhập ngày (nếu có) thì không submit form bất ngờ
    var dateInputs = document.querySelectorAll('input[type="date"]');
    dateInputs.forEach(function (input) {
        input.addEventListener('keydown', function (e) {
            if (e.key === 'Enter') {
                e.preventDefault();
                var form = input.closest('form');
                if (form) form.requestSubmit ? form.requestSubmit() : form.submit();
            }
        });
    });

    // Tự đặt giá trị mặc định là hôm nay cho ô ngày nếu đang trống
    document.addEventListener('DOMContentLoaded', function () {
        var today = new Date().toISOString().split('T')[0];
        dateInputs.forEach(function (input) {
            if (!input.value) input.value = today;
        });
    });

    // Khi click vào bài viết trong danh sách: đánh dấu đã đọc ngay (AJAX, không chặn điều hướng)
    // và tô màu item đó để người dùng biết đã đọc. Đồng thời lưu id bài vừa đọc để
    // khi quay lại danh sách có thể cuộn đến đúng item này.
    document.addEventListener('click', function (e) {
        var card = e.target.closest('.article-card');
        if (!card) return;
        var href = card.getAttribute('href');
        if (!href) return;
        var id = card.getAttribute('data-id');
        if (!id) {
            var idMatch = href.match(/\/news\/(\d+)(\?|$)/);
            if (!idMatch) return;
            id = idMatch[1];
        }

        // Gửi yêu cầu đánh dấu đã đọc lên server. keepalive giúp yêu cầu vẫn được gửi
        // khi trình duyệt đang rời trang để mở chi tiết bài viết.
        fetch('/news/' + id + '/read', { method: 'POST', keepalive: true })
            .catch(function () {
                // Bỏ qua lỗi mạng; trạng thái sẽ được đồng bộ lại khi tải lại danh sách.
            });

        // Lưu id bài vừa đọc vào sessionStorage để khôi phục vị trí cuộn khi quay lại.
        sessionStorage.setItem('news_last_read_id', id);

        // Phản hồi trực quan ngay lập tức trên item vừa click.
        markCardAsRead(card);
    });

    // Đánh dấu một item là đã đọc: thêm class màu + chèn nhãn "Đã đọc" nếu chưa có.
    function markCardAsRead(card) {
        if (!card) return;
        card.classList.add('is-read');
        ensureReadBadge(card);
    }

    // Chèn nhãn "Đã đọc" vào footer item nếu chưa có.
    function ensureReadBadge(card) {
        if (!card) return;
        var footer = card.querySelector('.article-card__footer');
        if (!footer) return;
        if (footer.querySelector('.is-read-badge')) return;

        var badge = document.createElement('span');
        badge.className = 'badge is-read-badge';
        badge.textContent = 'Đã đọc';

        var firstBadge = footer.querySelector('.badge');
        if (firstBadge) {
            firstBadge.after(badge);
        } else {
            footer.appendChild(badge);
        }
    }

    // Cuộn đến item vừa đọc (nếu có) khi trang danh sách hiển thị.
    // Hàm này được gọi cả khi load thường và khi khôi phục từ back-forward cache (nhấn Back).
    function scrollToLastRead() {
        var grid = document.querySelector('.articles-grid');
        if (!grid) return;

        var lastReadId = sessionStorage.getItem('news_last_read_id');
        if (!lastReadId) return;

        var card = grid.querySelector('.article-card[data-id="' + lastReadId + '"]');
        if (card) {
            card.scrollIntoView({ behavior: 'auto', block: 'center' });
            // Đảm bảo nhãn "Đã đọc" xuất hiện (quy yếu khi khôi phục từ back-forward cache).
            markCardAsRead(card);
        }
        sessionStorage.removeItem('news_last_read_id');
    }

    // Khi tải trang danh sách thường.
    document.addEventListener('DOMContentLoaded', function () {
        var today = new Date().toISOString().split('T')[0];
        dateInputs.forEach(function (input) {
            if (!input.value) input.value = today;
        });
        scrollToLastRead();
    });

    // Khi nhấn Back/Forward: trình duyệt có thể khôi phục trang từ back-forward cache,
    // lúc đó DOMContentLoaded không chạy lại → dùng sự kiện pageshow.
    window.addEventListener('pageshow', function (e) {
        if (e.persisted) {
            scrollToLastRead();
        }
    });

    // Trang chi tiết: không hiển thị ảnh đại diện (.detail-image) ở trên cùng,
    // chỉ giữ các ảnh bên trong nội dung bài viết (.detail-content).
    document.addEventListener('DOMContentLoaded', function () {
        var detailImage = document.querySelector('.detail-image');
        if (detailImage) {
            detailImage.style.display = 'none';
        }
    });

    // ---------- TTS (Text-to-Speech) miễn phí bằng Web Speech API ----------
    (function initTTS() {
        if (!window.speechSynthesis) {
            console.log('[TTS] window.speechSynthesis không tồn tại trên trình duyệt này.');
            return;
        }
        var synth = window.speechSynthesis;

        var playBtn = document.getElementById('tts-play');
        var pauseBtn = document.getElementById('tts-pause');
        var stopBtn = document.getElementById('tts-stop');
        var statusEl = document.getElementById('tts-status');
        var contentEl = document.querySelector('.detail-content');

        console.log('[TTS] init: playBtn=', !!playBtn, '| pauseBtn=', !!pauseBtn, '| stopBtn=', !!stopBtn, '| statusEl=', !!statusEl, '| contentEl=', !!contentEl);

        if (!playBtn || !contentEl) {
            console.log('[TTS] Thiếu nút điều khiển hoặc .detail-content, bỏ qua init TTS.');
            return;
        }

        console.log('[TTS] contentEl tagName=', contentEl.tagName, '| innerText length=', (contentEl.innerText || '').length);

        var utterance = null;
        var isPaused = false;
        var voicesReady = false;
        var currentMark = null;
        var segments = [];
        var currentSegmentIdx = -1;
        var boundaryTimer = null;
        var isManuallyStopped = false;

        // Xóa highlight cũ (chỉ để 1 đoạn đang đọc được highlight tại một thời điểm).
        function clearHighlight() {
            try {
                if (currentMark) {
                    var parent = currentMark.parentNode;
                    if (parent) {
                        parent.replaceChild(document.createTextNode(currentMark.textContent), currentMark);
                        parent.normalize();
                    }
                    currentMark = null;
                }
            } catch (e) {
                console.error('[TTS] clearHighlight error:', e);
            }
        }

        // Highlight node text chứa absoluteCharIndex trong full text (chỉ 1 mark tại 1 thời điểm).
        function highlightAbsoluteCharIndex(absIndex) {
            if (!contentEl) return;
            try {
                var index = 0;
                var walker = document.createTreeWalker(contentEl, NodeFilter.SHOW_TEXT, null, false);
                var node;
                var foundNode = null;

                while ((node = walker.nextNode())) {
                    var len = (node.nodeValue || '').length;
                    if (absIndex >= index && absIndex <= index + len) {
                        foundNode = node;
                        break;
                    }
                    index += len;
                }

                if (!foundNode) return;

                // Nếu vẫn cùng node, không cần làm lại (tránh lặp highlight).
                if (currentMark && currentMark.textContent === foundNode.nodeValue) return;

                clearHighlight();

                var mark = document.createElement('mark');
                mark.className = 'tts-speaking';
                mark.textContent = foundNode.nodeValue;
                foundNode.parentNode.replaceChild(mark, foundNode);
                currentMark = mark;

                console.log('[TTS] highlighting absIndex=', absIndex, 'text=', mark.textContent.substring(0, 40));

                // Dùng setTimeout 0 để không block luồng TTS trên Android.
                setTimeout(function () {
                    try { mark.scrollIntoView({ behavior: 'smooth', block: 'center' }); } catch (e) {}
                }, 0);
            } catch (e) {
                console.error('[TTS] highlightAbsoluteCharIndex error:', e);
            }
        }

        // Tách văn bản thành các câu theo dấu câu (. ! ? ...)
        function splitSentences(text) {
            var result = [];
            var current = '';
            for (var i = 0; i < text.length; i++) {
                current += text[i];
                if ('.!?'.indexOf(text[i]) !== -1) {
                    if (i + 1 >= text.length || text[i + 1] === ' ' || text[i + 1] === '\n' || text[i + 1] === '\r') {
                        result.push(current);
                        current = '';
                        while (i + 1 < text.length && (text[i + 1] === ' ' || text[i + 1] === '\n' || text[i + 1] === '\r')) i++;
                    }
                }
            }
            if (current.trim()) result.push(current);
            return result;
        }

        // Chia nội dung theo thẻ <p>, mỗi <p> tách thêm theo câu.
        // Mỗi câu là một segment với startIndex trong full text (dùng để highlight).
        function splitIntoParagraphSegments(contentEl) {
            var segments = [];
            var paragraphs = contentEl.querySelectorAll('p');
            var globalIndex = 0;

            if (paragraphs.length > 0) {
                paragraphs.forEach(function (p) {
                    var pText = p.textContent || '';
                    if (!pText.trim()) {
                        globalIndex += 1;
                        return;
                    }
                    var sentences = splitSentences(pText);
                    sentences.forEach(function (s) {
                        var sTrim = s.trim();
                        if (sTrim) {
                            segments.push({
                                text: sTrim,
                                startIndex: globalIndex,
                                endIndex: globalIndex + sTrim.length
                            });
                            globalIndex += sTrim.length + 1; // +1 khoảng trắng giữa các câu
                        }
                    });
                    globalIndex += 1; // khoảng cách giữa các đoạn <p>
                });
            }

            // Nếu không có thẻ p, fallback lấy toàn bộ text và tách câu.
            if (segments.length === 0) {
                var fullText = contentEl.textContent || '';
                var sentences = splitSentences(fullText);
                var idx = 0;
                sentences.forEach(function (s) {
                    var sTrim = s.trim();
                    if (sTrim) {
                        segments.push({
                            text: sTrim,
                            startIndex: idx,
                            endIndex: idx + sTrim.length
                        });
                        idx += sTrim.length + 1;
                    }
                });
            }

            return segments;
        }

        function setStatus(msg) {
            if (statusEl) statusEl.textContent = msg;
            console.log('[TTS] status:', msg);
        }

        function resetButtons() {
            playBtn.textContent = '🔊 Nghe bài';
            playBtn.disabled = false;
            pauseBtn.disabled = true;
            stopBtn.disabled = true;
            isPaused = false;
            console.log('[TTS] buttons reset.');
        }

        // Chọn giọng tiếng Việt nếu có.
        function pickVoice(utt) {
            try {
                var voices = synth.getVoices() || [];
                console.log('[TTS] pickVoice: available voices count=', voices.length);
                voices.forEach(function (v, i) {
                    console.log('[TTS]   voice[' + i + ']: name=', v.name, '| lang=', v.lang, '| default=', v.default);
                });
                var viVoice = voices.find(function (v) {
                    return /^vi/i.test(v.lang) || /vietnamese/i.test(v.name);
                });
                if (viVoice) {
                    utt.voice = viVoice;
                    console.log('[TTS] pickVoice: selected Vietnamese voice=', viVoice.name, '| lang=', viVoice.lang);
                } else if (voices.length > 0) {
                    utt.lang = 'vi';
                    console.log('[TTS] pickVoice: no Vietnamese voice, fallback lang=vi');
                } else {
                    console.log('[TTS] pickVoice: no voices available yet');
                }
            } catch (e) {
                console.error('[TTS] pickVoice error:', e);
            }
        }

        // Chờ giọng TTS được nạp.
        synth.addEventListener('voiceschanged', function () {
            voicesReady = true;
            console.log('[TTS] voiceschanged fired, count=', (synth.getVoices() || []).length);
        });

        // Đọc 1 câu/đoạn theo chỉ số.
        function speakSegment(idx) {
            if (idx >= segments.length) {
                clearHighlight();
                resetButtons();
                setStatus('Đã đọc xong.');
                return;
            }

            currentSegmentIdx = idx;
            var seg = segments[idx];
            console.log('[TTS] speakSegment idx=', idx, '| startIndex=', seg.startIndex, '| text length=', seg.text.length, '| preview:', seg.text.substring(0, 60));

            clearHighlight();

            utterance = new SpeechSynthesisUtterance(seg.text);
            pickVoice(utterance);
            utterance.rate = 1;
            utterance.pitch = 1;
            utterance.volume = 1;

            utterance.onstart = function () {
                console.log('[TTS] onstart fired segment=', idx);
                playBtn.textContent = '🔊 Đang đọc';
                playBtn.disabled = true;
                pauseBtn.disabled = false;
                stopBtn.disabled = false;
                setStatus('Đang đọc bài viết (câu ' + (idx + 1) + '/' + segments.length + ')...');

                // Reset boundary timer cho segment mới.
                if (boundaryTimer) clearTimeout(boundaryTimer);
                boundaryTimer = setTimeout(function () {
                    console.log('[TTS] boundary timeout: no onboundary fired for 2000ms, highlight whole segment');
                    highlightAbsoluteCharIndex(seg.startIndex);
                }, 2000);
            };

            // Highlight từng câu khi TTS đang đọc (chỉ 1 mark tại 1 thời điểm).
            utterance.onboundary = function (event) {
                console.log('[TTS] onboundary segment=', idx, 'charIndex=', event.charIndex, 'type=', event.type);
                if (boundaryTimer) clearTimeout(boundaryTimer);
                try { highlightAbsoluteCharIndex(seg.startIndex + event.charIndex); } catch (e) {}
            };

            utterance.onend = function () {
                console.log('[TTS] onend fired segment=', idx, '| isManuallyStopped=', isManuallyStopped);
                if (boundaryTimer) clearTimeout(boundaryTimer);
                clearHighlight();
                // Nếu người dùng đã nhấn Dừng, không tự động đọc câu tiếp theo.
                if (isManuallyStopped) return;
                // Tự động chuyển sang câu tiếp theo.
                speakSegment(idx + 1);
            };

            utterance.onerror = function (event) {
                console.error('[TTS] onerror fired segment=', idx, ':', event.error, event, '| isManuallyStopped=', isManuallyStopped);
                if (boundaryTimer) clearTimeout(boundaryTimer);
                clearHighlight();

                // Nếu người dùng đã nhấn Dừng, không tự động đọc câu tiếp theo.
                if (isManuallyStopped) return;

                if (event.error === 'synthesis-failed') {
                    // Thử lại với lang khác nếu chưa thử đủ
                    var langs = ['vi', 'vi-VN', ''];
                    var currentLangIdx = langs.indexOf(utterance.lang);
                    var nextLang = (currentLangIdx >= 0 && currentLangIdx < langs.length - 1) ? langs[currentLangIdx + 1] : null;

                    if (nextLang !== null) {
                        console.log('[TTS] synthesis-failed segment=', idx, 'retrying with lang=', nextLang);
                        var retryUtt = new SpeechSynthesisUtterance(seg.text);
                        retryUtt.lang = nextLang;
                        retryUtt.rate = 1;
                        retryUtt.pitch = 1;
                        retryUtt.volume = 1;
                        retryUtt.onstart = function () {
                            console.log('[TTS] retry onstart fired lang=', nextLang);
                            playBtn.textContent = '🔊 Đang đọc';
                            playBtn.disabled = true;
                            pauseBtn.disabled = false;
                            stopBtn.disabled = false;
                            setStatus('Đang đọc bài viết (câu ' + (idx + 1) + '/' + segments.length + ')...');
                        };
                        retryUtt.onboundary = function (event) {
                            if (boundaryTimer) clearTimeout(boundaryTimer);
                            try { highlightAbsoluteCharIndex(seg.startIndex + event.charIndex); } catch (e) {}
                        };
                        retryUtt.onend = function () {
                            console.log('[TTS] retry onend fired lang=', nextLang);
                            clearHighlight();
                            speakSegment(idx + 1);
                        };
                        retryUtt.onerror = function (ev) {
                            console.error('[TTS] retry onerror fired lang=', nextLang, ':', ev.error);
                            clearHighlight();
                            // Bỏ qua lỗi, chuyển sang câu tiếp theo để không bị kẹt.
                            speakSegment(idx + 1);
                        };
                        utterance = retryUtt;
                        synth.speak(retryUtt);
                        console.log('[TTS] synth.speak retry called lang=', nextLang);
                        return;
                    }

                    setStatus('Thiết bị này chưa hỗ trợ đọc giọng nói (TTS). Hãy bật TTS trong cài đặt hệ thống.');
                    // Tiếp tục đọc câu sau để không bị kẹt hoàn toàn.
                    speakSegment(idx + 1);
                } else {
                    setStatus('Lỗi khi đọc bài viết: ' + event.error);
                    speakSegment(idx + 1);
                }
            };

            // Nếu voices chưa sẵn sàng, chờ một chút rồi mới speak.
            if (!voicesReady && (synth.getVoices() || []).length === 0) {
                console.log('[TTS] voices not ready yet, waiting 500ms before speak segment=', idx);
                setTimeout(function () {
                    synth.speak(utterance);
                    console.log('[TTS] synth.speak called segment=', idx);
                }, 500);
            } else {
                synth.speak(utterance);
                console.log('[TTS] synth.speak called segment=', idx);
            }
        }

        playBtn.addEventListener('click', function () {
            try {
                console.log('[TTS] play clicked');
                console.log('[TTS] synth state - speaking:', synth.speaking, '| pending:', synth.pending, '| paused:', synth.paused);

                // Reset flag dừng thủ công khi bắt đầu đọc mới.
                isManuallyStopped = false;

                // Tiếp tục sau khi tạm dừng (hoặc sau khi Dừng): luôn khởi tạo utterance mới
                // từ câu hiện tại, vì synth.resume() trên Android Chrome rất unreliable.
                if (isPaused && currentSegmentIdx >= 0) {
                    console.log('[TTS] resuming from paused segment', currentSegmentIdx);
                    isPaused = false;
                    synth.cancel();
                    speakSegment(currentSegmentIdx);
                    return;
                }

                // Nếu đã chia segments và đang dừng ở câu nào đó (sau khi nhấn Dừng), tiếp tục từ câu đó
                if (!isPaused && segments.length > 0 && currentSegmentIdx >= 0) {
                    console.log('[TTS] resuming from stopped segment', currentSegmentIdx);
                    speakSegment(currentSegmentIdx);
                    return;
                }

                // Chỉ cancel khi thực sự có utterance đang chạy
                if (synth.speaking || synth.pending) {
                    console.log('[TTS] canceling existing speech before new speak');
                    synth.cancel();
                }

                clearHighlight();

                // Chia nội dung theo <p> và câu nếu chưa có.
                if (segments.length === 0) {
                    console.log('[TTS] splitting content into paragraphs and sentences...');
                    segments = splitIntoParagraphSegments(contentEl);
                    console.log('[TTS] split into', segments.length, 'segments (câu/đoạn)');
                }

                if (segments.length === 0) {
                    setStatus('Không có nội dung để đọc.');
                    return;
                }

                currentSegmentIdx = 0;
                speakSegment(0);
            } catch (e) {
                console.error('[TTS] play click error:', e);
                clearHighlight();
                resetButtons();
                setStatus('Trình duyệt không hỗ trợ đọc bài viết.');
            }
        });

        pauseBtn.addEventListener('click', function () {
            try {
                console.log('[TTS] pause clicked');
                if (synth.speaking && !synth.paused) {
                    synth.pause();
                    isPaused = true;
                    playBtn.textContent = '▶ Tiếp tục';
                    playBtn.disabled = false;
                    setStatus('Đã tạm dừng.');
                }
            } catch (e) {
                console.error('[TTS] pause click error:', e);
            }
        });

        stopBtn.addEventListener('click', function () {
            try {
                console.log('[TTS] stop clicked');
                synth.cancel();
                clearHighlight();
                resetButtons();
                // Xóa hết để lần sau nhấn Nghe bài sẽ đọc lại từ đầu.
                segments = [];
                currentSegmentIdx = -1;
                isManuallyStopped = false;
                utterance = null;
                setStatus('Đã dừng.');
            } catch (e) {
                console.error('[TTS] stop click error:', e);
            }
        });
    })();

    // ---------- Gán sự kiện cho danh sách tin (thay vì inline HTML onclick) ----------
    document.addEventListener('DOMContentLoaded', function () {
        // Click item tin tức -> chuyển trang chi tiết (trừ khi click từ nút TTS).
        document.querySelectorAll('.article-card').forEach(function (card) {
            card.addEventListener('click', function (e) {
                if (e.target.closest('.tts-btn')) return;
                var id = card.getAttribute('data-id');
                console.log('[ListTTS] card click id=', id);
                location.href = '/news/' + id;
            });
        });

        // Click nút TTS trong mỗi item.
        document.querySelectorAll('.tts-btn').forEach(function (btn) {
            btn.addEventListener('click', function (e) {
                e.stopPropagation();
                e.preventDefault();
                var id = btn.getAttribute('data-id');
                console.log('[ListTTS] tts-btn click id=', id);

                // Đánh dấu đã đọc trong DB và đổi màu item ngay lập tức (giống click vào item thường).
                var card = btn.closest('.article-card');
                fetch('/news/' + id + '/read', { method: 'POST', keepalive: true })
                    .catch(function () {});
                if (card) {
                    markCardAsRead(card);
                }

                toggleTts(btn, id);
            });
        });
    });

    // ---------- Chuyển trang chi tiết khi click item tin ----------
    window.openArticle = function (id) {
        console.log('[ListTTS] openArticle called id=', id);
        location.href = '/news/' + id;
    };

    // ---------- TTS (Text-to-Speech) cho DANH SÁCH tin tức ----------
    (function initListTTS() {
        if (!window.speechSynthesis) {
            console.log('[ListTTS] window.speechSynthesis không tồn tại trên trình duyệt này — TTS bị bỏ qua.');
            return;
        }
        console.log('[ListTTS] speechSynthesis supported, init TTS controls.');
        var synth = window.speechSynthesis;
        var currentUtterance = null;
        var currentItemId = null;
        var currentSegments = [];
        var currentSegIdx = 0;
        var isPaused = false;
        var isPlaying = false;

        function stopCurrent() {
            console.log('[ListTTS] stopCurrent called');
            try { if (synth.speaking || synth.paused) synth.cancel(); } catch (e) {}
            if (currentUtterance) {
                currentUtterance.onend = null;
                currentUtterance.onerror = null;
                currentUtterance = null;
            }
            setListTtsButton(null, 'idle');
            currentItemId = null;
            currentSegments = [];
            currentSegIdx = 0;
            isPaused = false;
            isPlaying = false;
        }

        function setListTtsButton(activeId, state) {
            document.querySelectorAll('.tts-btn').forEach(function (btn) {
                var id = btn.getAttribute('data-id');
                if (id == null) return;
                var isActive = (activeId != null && id == activeId);
                if (!isActive) {
                    // Không disable nút item khác — cho phép click để chuyển bài.
                    btn.disabled = false;
                    btn.classList.remove('playing', 'paused');
                    btn.textContent = '🔊 Nghe bài';
                    return;
                }
                if (state === 'playing') {
                    btn.textContent = '⏸ Tạm dừng';
                    btn.classList.add('playing');
                    btn.classList.remove('paused');
                    btn.disabled = false;
                } else if (state === 'paused') {
                    btn.textContent = '▶ Tiếp tục';
                    btn.classList.add('paused');
                    btn.classList.remove('playing');
                    btn.disabled = false;
                } else {
                    btn.textContent = '🔊 Nghe bài';
                    btn.classList.remove('playing', 'paused');
                    btn.disabled = false;
                }
            });
        }

        function speakText(itemId, text) {
            console.log('[ListTTS] speakText id=', itemId, '| text length=', text.length);
            stopCurrent();
            currentItemId = itemId;
            isPaused = false;
            isPlaying = true;
            setListTtsButton(itemId, 'playing');

            // Tách thành các câu nhỏ để tránh synthesis-failed khi đoạn quá dài
            // hoặc khi trình duyệt chưa sẵn sàng giọng đọc (giống trang chi tiết).
            currentSegments = splitListSentences(text);
            console.log('[ListTTS] split into', currentSegments.length, 'sentences for id=', itemId);
            if (currentSegments.length === 0) {
                setListTtsButton(itemId, 'idle');
                currentItemId = null;
                isPlaying = false;
                isPaused = false;
                return;
            }

            currentSegIdx = 0;
            speakNext();
        }

        // Tiếp tục đọc từ câu hiện tại (currentSegIdx) mà không reset lại đầu.
        function speakRemaining() {
            if (currentSegIdx >= currentSegments.length) {
                console.log('[ListTTS] all segments done id=', currentItemId);
                isPlaying = false;
                isPaused = false;
                setListTtsButton(currentItemId, 'idle');
                currentItemId = null;
                currentSegments = [];
                currentSegIdx = 0;
                return;
            }
            console.log('[ListTTS] speakRemaining from segIdx', currentSegIdx, 'id=', currentItemId);
            speakNext();
        }

        function speakNext() {
            if (currentSegIdx >= currentSegments.length) {
                console.log('[ListTTS] all segments done id=', currentItemId);
                isPlaying = false;
                isPaused = false;
                setListTtsButton(currentItemId, 'idle');
                currentItemId = null;
                currentSegments = [];
                currentSegIdx = 0;
                return;
            }
            var seg = currentSegments[currentSegIdx];
            console.log('[ListTTS] speak segment', currentSegIdx, '/', currentSegments.length, 'id=', currentItemId, '| text=', seg.substring(0, 60));

            var utterance = new SpeechSynthesisUtterance(seg);
            var voices = synth.getVoices() || [];
            var viVoice = voices.find(function (v) {
                return /^vi/i.test(v.lang) || /vietnamese/i.test(v.name);
            });
            if (viVoice) {
                utterance.voice = viVoice;
            } else {
                utterance.lang = 'vi';
            }
            utterance.rate = 1;
            utterance.pitch = 1;
            utterance.volume = 1;

            utterance.onend = function () {
                currentSegIdx++;
                speakNext();
            };
            utterance.onerror = function (err) {
                console.error('[ListTTS] segment error id=', currentItemId, 'seg=', currentSegIdx, err.error);
                // Thử lại với lang khác nếu là synthesis-failed
                if (err.error === 'synthesis-failed') {
                    var langs = ['vi', 'vi-VN', ''];
                    var tried = utterance.lang || '';
                    var nextLang = null;
                    for (var i = 0; i < langs.length; i++) {
                        if (langs[i] === tried) {
                            nextLang = langs[i + 1];
                            break;
                        }
                    }
                    if (nextLang !== null) {
                        console.log('[ListTTS] retry segment with lang=', nextLang);
                        var retry = new SpeechSynthesisUtterance(seg);
                        retry.lang = nextLang;
                        retry.rate = 1; retry.pitch = 1; retry.volume = 1;
                        retry.onend = function () { currentSegIdx++; speakNext(); };
                        retry.onerror = function () { currentSegIdx++; speakNext(); };
                        currentUtterance = retry;
                        try { synth.speak(retry); } catch (e) { currentSegIdx++; speakNext(); }
                        return;
                    }
                }
                // Bỏ qua câu này, sang câu tiếp theo để không kẹt.
                currentSegIdx++;
                speakNext();
            };

            currentUtterance = utterance;
            try {
                synth.speak(utterance);
                console.log('[ListTTS] synth.speak segment', currentSegIdx, 'id=', currentItemId);
            } catch (e) {
                console.error('[ListTTS] speak segment exception', e);
                currentSegIdx++;
                speakNext();
            }
        }

        function splitListSentences(text) {
            if (!text) return [];
            var result = [];
            var current = '';
            for (var i = 0; i < text.length; i++) {
                current += text[i];
                if ('.!?'.indexOf(text[i]) !== -1) {
                    if (i + 1 >= text.length || text[i + 1] === ' ' || text[i + 1] === '\n' || text[i + 1] === '\r') {
                        var s = current.trim();
                        if (s) result.push(s);
                        current = '';
                        while (i + 1 < text.length && (text[i + 1] === ' ' || text[i + 1] === '\n' || text[i + 1] === '\r')) i++;
                    }
                }
            }
            var last = current.trim();
            if (last) result.push(last);
            return result;
        }

        function speakTextFromBtn(btn, id) {
            console.log('[ListTTS] speakTextFromBtn id=', id);
            fetch('/news/' + id + '/content')
                .then(function (resp) {
                    console.log('[ListTTS] fetch status=', resp.status);
                    return resp.json();
                })
                .then(function (data) {
                    console.log('[ListTTS] fetch data=', data);
                    if (data.error) {
                        btn.textContent = '🔊 Nghe bài';
                        console.error('[ListTTS]', data.error);
                        return;
                    }
                    var text = (data.title || '') + '. ' + (data.content || '');
                    if (!text.trim()) {
                        btn.textContent = '🔊 Nghe bài';
                        console.warn('[ListTTS] empty content for id=', id);
                        return;
                    }
                    speakText(id, text);
                })
                .catch(function (err) {
                    console.error('[ListTTS] fetch error', err);
                    btn.textContent = '🔊 Nghe bài';
                });
        }

        // Click nút "Nghe bài / Tạm dừng / Tiếp tục".
        window.toggleTts = function (btn, id) {
            console.log('[ListTTS] toggleTts clicked id=', id, '| isPlaying=', isPlaying, '| isPaused=', isPaused, '| currentItemId=', currentItemId);

            // Luôn dừng item KHÁC trước khi bắt đầu item mới (đọc hoặc tạm dừng đều dừng hết).
            if (currentItemId != null && currentItemId != id) {
                console.log('[ListTTS] switching from item', currentItemId, 'to', id);
                stopCurrent();
            }

            // Đang tạm dừng item NÀY -> tiếp tục đọc từ câu hiện tại.
            if (currentItemId == id && isPaused && !isPlaying) {
                console.log('[ListTTS] resume item', id, '| segIdx=', currentSegIdx, '/', currentSegments.length);
                isPaused = false;
                isPlaying = true;
                setListTtsButton(id, 'playing');

                if (currentUtterance) {
                    currentUtterance.onend = null;
                    currentUtterance.onerror = null;
                    currentUtterance = null;
                }
                try { synth.cancel(); } catch (e) {}

                speakRemaining();
                return;
            }

            // ĐANG ĐỌC item NÀY -> tạm dừng.
            if (currentItemId == id && isPlaying && !isPaused) {
                console.log('[ListTTS] pause item', id);
                try { synth.pause(); } catch (e) {}
                isPaused = true;
                isPlaying = false;
                setListTtsButton(id, 'paused');
                return;
            }

            // Mặc định (chưa đọc / đã đọc xong) -> bắt đầu đọc item này.
            console.log('[ListTTS] start new speak item', id);
            speakTextFromBtn(btn, id);
        };

        // Quay về trang tin tức giữ lại chuyên mục/ngày đã chọn (dùng trên trang admin).
        window.goBackToNews = function (e) {
            if (e) { e.preventDefault(); e.stopPropagation(); }
            var savedDate = sessionStorage.getItem('newsDate');
            var savedCat = sessionStorage.getItem('newsCategoryId');
            var qs = [];
            if (savedDate) qs.push('date=' + encodeURIComponent(savedDate));
            if (savedCat) qs.push('categoryId=' + encodeURIComponent(savedCat));
            window.location.href = '/news' + (qs.length ? '?' + qs.join('&') : '');
            return false;
        };
    })();

    // ---------- Cap nhat tin tung nguon (bat dong bo, cho phep nhieu nguon cung luc) ----------
    (function initSourceUpdate() {
        var START_URL = '/admin/update-source/';
        var STATUS_URL = '/admin/update-source/';
        var POLL_INTERVAL = 2000;
        var MAX_POLL = 600;
        var HIDE_DELAY_DONE = 6000;
        var HIDE_DELAY_ERROR = 8000;

        // jobStates[sourceId] = { polling: boolean, pollCount: number, timer: number|null }
        var jobStates = {};

        function ensureState(sourceId) {
            if (!jobStates[sourceId]) {
                jobStates[sourceId] = { polling: false, pollCount: 0, timer: null, hideTimer: null };
            }
            return jobStates[sourceId];
        }

        function btnOf(sourceId) {
            return document.querySelector('.btn-update-source[data-id=' + sourceId + ']');
        }
        function statusOf(sourceId) {
            return document.querySelector('.job-status[data-id=' + sourceId + ']');
        }

        function clearHideTimer(sourceId) {
            var state = jobStates[sourceId];
            if (state && state.hideTimer) {
                clearTimeout(state.hideTimer);
                state.hideTimer = null;
            }
        }

        function setStatusHtml(sourceId, html, cls) {
            var el = statusOf(sourceId);
            if (!el) return;
            clearHideTimer(sourceId);
            el.innerHTML = html || '';
            el.className = 'job-status' + (cls ? ' ' + cls : '');
        }

        function scheduleStatusHide(sourceId, delay) {
            var state = ensureState(sourceId);
            clearHideTimer(sourceId);
            state.hideTimer = setTimeout(function () {
                state.hideTimer = null;
                setStatusHtml(sourceId, '', '');
            }, delay);
        }

        function setBtnState(sourceId, running) {
            var btn = btnOf(sourceId);
            if (!btn) return;
            btn.disabled = running;
            if (running) {
                btn.classList.add('btn-disabled', 'spinning');
                btn.title = 'Đang cập nhật, vui lòng đợi...';
            } else {
                btn.classList.remove('btn-disabled', 'spinning');
                btn.title = 'Cập nhật tin của ' + (btn.getAttribute('data-name') || '');
            }
        }

        function pollStatus(sourceId, sourceName) {
            var state = jobStates[sourceId];
            if (!state || !state.polling) return;
            state.pollCount = (state.pollCount || 0) + 1;

            fetch(STATUS_URL + sourceId + '/status')
                .then(function (resp) { return resp.json(); })
                .then(function (data) {
                    var st = data.status;
                    if (st === 'running') {
                        setStatusHtml(sourceId,
                            '<span class="status running">&#x23F3; Đang cập nhật...</span>', 'running');
                        if (state.pollCount < MAX_POLL) {
                            state.timer = setTimeout(function () {
                                pollStatus(sourceId, sourceName);
                            }, POLL_INTERVAL);
                        } else {
                            setStatusHtml(sourceId,
                                '<span class="status warn">&#x26A0; Quá thời gian chờ (công việc còn có thể đang chạy trên server)</span>', 'warn');
                            setBtnState(sourceId, false);
                            state.polling = false;
                        }
                    } else if (st === 'done') {
                        setBtnState(sourceId, false);
                        state.polling = false;
                        if (state.timer) { clearTimeout(state.timer); state.timer = null; }
                        var inserted = data.inserted == null ? 0 : data.inserted;
                        var found = data.found == null ? 0 : data.found;
                        var note = data.note || 'OK';
                        setStatusHtml(sourceId,
                            '<span class="status ok">&#x2705; Xong: ' + inserted + '/' + found + ' bài (' + note + ')</span>', 'ok');
                        scheduleStatusHide(sourceId, HIDE_DELAY_DONE);
                    } else if (st === 'error') {
                        setBtnState(sourceId, false);
                        state.polling = false;
                        if (state.timer) { clearTimeout(state.timer); state.timer = null; }
                        setStatusHtml(sourceId,
                            '<span class="status err">&#x274C; Lỗi: ' + (data.error || 'Lỗi không xác định') + '</span>', 'err');
                        scheduleStatusHide(sourceId, HIDE_DELAY_ERROR);
                    } else {
                        // idle / chua co job
                        setBtnState(sourceId, false);
                        state.polling = false;
                    }
                })
                .catch(function (err) {
                    console.error('[SourceUpdate] poll error sourceId=', sourceId, err);
                    var state2 = jobStates[sourceId];
                    if (state2 && state2.polling && (state2.pollCount || 0) < MAX_POLL) {
                        state2.timer = setTimeout(function () {
                            pollStatus(sourceId, sourceName);
                        }, POLL_INTERVAL);
                    }
                });
        }

        document.addEventListener('click', function (e) {
            var btn = e.target.closest('.btn-update-source');
            if (!btn) return;
            e.preventDefault();
            e.stopPropagation();

            var sourceId = btn.getAttribute('data-id');
            var sourceName = btn.getAttribute('data-name') || 'Nguon';
            var state = jobStates[sourceId];
            if (state && state.polling) return; // dang chay, bo qua

            setBtnState(sourceId, true);
            setStatusHtml(sourceId,
                '<span class="status running">&#x23F3; Dang gui yeu cau...</span>', 'running');
            state = ensureState(sourceId);
            if (state.timer) { clearTimeout(state.timer); state.timer = null; }
            state.polling = true;
            state.pollCount = 0;

            fetch(START_URL + sourceId, { method: 'POST',
                headers: { 'X-Requested-With': 'fetch' } })
                .then(function (resp) { return resp.json(); })
                .then(function (data) {
                    var st = data.status;
                    if (st === 'started' || st === 'running') {
                        pollStatus(sourceId, sourceName);
                    } else {
                        setBtnState(sourceId, false);
                        ensureState(sourceId).polling = false;
                        setStatusHtml(sourceId,
                            '<span class="status err">&#x274C; Khong the bat dau cap nhat</span>', 'err');
                        scheduleStatusHide(sourceId, HIDE_DELAY_ERROR);
                    }
                })
                .catch(function (err) {
                    console.error('[SourceUpdate] start error sourceId=', sourceId, err);
                    setBtnState(sourceId, false);
                    if (jobStates[sourceId]) jobStates[sourceId].polling = false;
                    setStatusHtml(sourceId,
                        '<span class="status err">&#x274C; Loi ket noi khi bat dau cap nhat</span>', 'err');
                    scheduleStatusHide(sourceId, HIDE_DELAY_ERROR);
                });
        });
    })();

    // ---------- An/hien bang thong ke ----------
    window.toggleDailyStats = function () {
        var panel = document.getElementById('dailyStatsPanel');
        if (!panel) return;
        if (panel.style.display === 'none') {
            panel.style.display = '';
        } else {
            panel.style.display = 'none';
        }
    };

    // ---------- An/hien chi tiet nguon theo chuyen muc ----------
    window.toggleCatDetail = function (catId) {
        var detailRow = document.getElementById('cat-detail-' + catId);
        var btn = document.getElementById('btn-cat-' + catId);
        if (!detailRow || !btn) return;

        // An tat ca hang con khac truoc khi hien hang duoc chon
        document.querySelectorAll('[id^="cat-detail-"]').forEach(function (row) {
            row.style.display = 'none';
        });
        document.querySelectorAll('[id^="btn-cat-"]').forEach(function (b) {
            b.textContent = '▶ Xem nguồn';
        });

        // Hien/an hang con cua chuyen muc duoc chon
        var hidden = detailRow.style.display === 'none';
        if (hidden) {
            detailRow.style.display = '';
            btn.textContent = '▼ Ẩn nguồn';
        } else {
            detailRow.style.display = 'none';
            btn.textContent = '▶ Xem nguồn';
        }
    };
})();
