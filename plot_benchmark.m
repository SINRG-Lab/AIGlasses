%{
 -- Utility for Parsing Serial Output and Plotting the Benchmark Results --

 Expected serial format (lines starting with "DATA,"):
   DATA,file,trial,enc_ms,up_ms,first_resp_ms,last_resp_ms,dec_ms,up_bytes,dl_bytes
   DATA,msg_small.wav,1,30,12238,12546,15850,2,115008,29440
   ...
   DATA,END

 Timing model:
   enc_ms         — PCM to mulaw encode (local, no network)
   up_ms          — wall-clock time to send all mulaw bytes
   first_resp_ms  — time from send-start to first audio byte received
   last_resp_ms   — time from send-start to AgentAudioDone / collect end
   dec_ms         — mulaw to PCM16 decode (local, no network)

 Because Deepgram streams responses during upload, first_resp_ms can be
 less than up_ms (response arrived before upload finished).
 We derive:
   network_ms = last_resp_ms  (covers upload + server + download, with overlap)
   total_ms   = enc_ms + last_resp_ms + dec_ms  (wall-clock end-to-end)
%}

filepath = fullfile(pwd, "serial_log.txt");

colors = [
    0.0784 0.1765 0.4118;     % navy blue
    0.5500 0.3500 0.7000;     % muted purple
    0.9000 0.7500 0.2000;     % muted yellow
    0.8000 0.2000 0.2000;     % muted red
    0.0000 0.5804 0.3686;     % velvet green
    0.4000 0.4000 0.4000;     % neutral gray
    0.2000 0.6000 0.7500;     % soft teal-blue
    0.8000 0.5000 0.0000;     % orange
    0.6000 0.2000 0.4500;     % wine / plum
    ];

%% ---- Parse DATA lines from serial log ----
fid = fopen(filepath, 'r');
if fid == -1
    error('Could not open %s', filepath);
end

files         = {};
trials        = [];
enc_ms        = [];
up_ms         = [];
first_resp_ms = [];
last_resp_ms  = [];
dec_ms        = [];
up_bytes      = [];
dl_bytes      = [];

while ~feof(fid)
    line = fgetl(fid);
    if ~ischar(line), continue; end
    if ~startsWith(line, 'DATA,'), continue; end
    if startsWith(line, 'DATA,file') || startsWith(line, 'DATA,END')
        continue;
    end

    parts = strsplit(line, ',');
    if numel(parts) < 10, continue; end

    files{end+1}         = parts{2};          %#ok<SAGROW>
    trials(end+1)        = str2double(parts{3});
    enc_ms(end+1)        = str2double(parts{4});
    up_ms(end+1)         = str2double(parts{5});
    first_resp_ms(end+1) = str2double(parts{6});
    last_resp_ms(end+1)  = str2double(parts{7});
    dec_ms(end+1)        = str2double(parts{8});
    up_bytes(end+1)      = str2double(parts{9});
    dl_bytes(end+1)      = str2double(parts{10});
end
fclose(fid);

N = numel(trials);
if N == 0
    error('No DATA lines found in %s', filepath);
end

unique_files = unique(files, 'stable');

%% ---- Derived metrics ----
% Wall-clock total
total_ms = enc_ms + last_resp_ms + dec_ms;

% Upload speed (bytes sent / upload wall-clock)
up_kbs = (up_bytes ./ up_ms) * 1000 / 1024;

% Download speed (bytes received / download wall-clock)
% Download duration = last_resp_ms - first_resp_ms (time receiving audio)
dl_dur_ms = last_resp_ms - first_resp_ms;
dl_kbs    = (dl_bytes ./ dl_dur_ms) * 1000 / 1024;

% For the stacked bar, break the network phase into three non-overlapping
% segments that sum to last_resp_ms:
%   1) upload-only   = time before first response arrives (upload still going)
%   2) overlap       = time both upload and download are active
%   3) download-only = time after upload finishes but download continues
% If first_resp < up, there is overlap; otherwise there is a gap (inference wait).
upload_only_ms   = min(first_resp_ms, up_ms);
overlap_ms       = max(0, up_ms - first_resp_ms);
download_only_ms = max(0, last_resp_ms - up_ms);
% The "inference gap" is time between upload done and first response, if any
infer_gap_ms     = max(0, first_resp_ms - up_ms);

%% ---- Figure 1: Stacked bar chart of total latency per trial ----
figure(1);
stage_matrix = [enc_ms; upload_only_ms; overlap_ms; infer_gap_ms; download_only_ms; dec_ms]' / 1000;

h = bar(1:N, stage_matrix, 'stacked');
ax = gca;

% Apply custom colors (rows of 'colors' correspond to stages)
% Ensure we have one color per stage; repeat or truncate as needed
numStages = numel(h);
if size(colors,1) < numStages
    % Repeat colors cyclically if not enough provided
    rep = ceil(numStages / size(colors,1));
    cmap = repmat(colors, rep, 1);
else
    cmap = colors;
end
cmap = cmap(1:numStages, :);

for k = 1:numStages
    h(k).FaceColor = 'flat';
    h(k).CData = repmat(cmap(k,:), N, 1);
end

legend({'Encode', 'Upload (before resp)', 'Upload+Download overlap', ...
        'Inference wait', 'Download (after upload)', 'Decode'}, ...
        'Location', 'northeastoutside');
xlabel('Trial');
ylabel('Latency (s)');
title('Per-Trial Latency Breakdown');
xticks(1:N);
grid on; box off;
ax.YAxis.Color = 'none';
ax.YLabel.Color = [0.15 0.15 0.15];
ax.YAxis.TickLabelColor = [0.15 0.15 0.15];
ax.XGrid = 'off';
ax.YGrid = 'on';

%% ---- Figure 2: Upload and download speed over trials ----
figure(2);
plot(1:N, up_kbs, '-o', 'Color', colors(3, :), 'LineWidth', 1.5);
hold on;
plot(1:N, dl_kbs, '-s', 'Color', colors(2, :), 'LineWidth', 1.5);
ax = gca;
legend({'Upload', 'Download'});
xlabel('Trial');
ylabel('Speed (KB/s)');
title('Upload / Download Speed per Trial');
xticks(1:N);
grid on; box off;
ax.YAxis.Color = 'none';
ax.YLabel.Color = [0.15 0.15 0.15];
ax.YAxis.TickLabelColor = [0.15 0.15 0.15];
ax.XGrid = 'off';
ax.YGrid = 'on';
hold off;

%% ---- Figure 3: Average % each stage takes of total pipeline ----
avg_enc      = mean(enc_ms          ./ total_ms) * 100;
avg_up_only  = mean(upload_only_ms  ./ total_ms) * 100;
avg_overlap  = mean(overlap_ms      ./ total_ms) * 100;
avg_infer    = mean(infer_gap_ms    ./ total_ms) * 100;
avg_dl_only  = mean(download_only_ms./ total_ms) * 100;
avg_dec      = mean(dec_ms          ./ total_ms) * 100;

figure(3);
b = bar([avg_enc, avg_up_only, avg_infer, avg_overlap, avg_dl_only, avg_dec]);
b.FaceColor = colors(1,:);
ax = gca;
xticklabels({'Encode', 'Upload', 'Inference', 'Overlap', 'Download', 'Decode'});
ylabel('% of Total Pipeline');
title('Average Stage Proportion');
grid on; box off;
ax.YAxis.Color = 'none';
ax.YLabel.Color = [0.15 0.15 0.15];
ax.YAxis.TickLabelColor = [0.15 0.15 0.15];
ax.XGrid = 'off';
ax.YGrid = 'on';
