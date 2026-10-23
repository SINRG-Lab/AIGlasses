%{
 -- Utility for Parsing Serial Output and Plotting the Benchmark Results --

 Expected serial format (lines starting with "DATA,"):
   DATA,file,trial,enc_ms,up_ms,first_resp_ms,last_resp_ms,dec_ms,up_bytes,dl_bytes
   DATA,msg_small.wav,1,30,12238,12546,15850,2,115008,29440
   ...
   DATA,END

 Timing model:
   enc_ms         — Don't worry about PCM to mulaw encode (local, no network)
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

%% ---- Color palette ----
% Clean 4-color palette (upload, overlap, inference, download)
% c_upload   = [0.220 0.557 0.835];  % steel blue
% c_overlap  = [0.584 0.345 0.698];  % soft purple
% c_infer    = [0.925 0.620 0.215];  % warm amber
% c_download = [0.204 0.694 0.490];  % sea green
c_sep      = [0.40  0.40  0.40 ];  % separator gray

colors = lines(5);
c_upload = colors(1,:);
c_overlap = colors(2,:);
c_infer = colors(3,:);
c_download = colors(4,:);

% Accent colors for line / box plots
% c_ul_line  = [0.220 0.557 0.835];  % matches upload
% c_dl_line  = [0.204 0.694 0.490];  % matches download
c_ul_line = colors(1,:);
c_dl_line = colors(4,:);

txt_color  = [0.15 0.15 0.15];

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

% For the stacked bar, break the network phase into non-overlapping segments:
upload_only_ms   = min(first_resp_ms, up_ms);
overlap_ms       = max(0, up_ms - first_resp_ms);
download_only_ms = max(0, last_resp_ms - up_ms);
infer_gap_ms     = max(0, first_resp_ms - up_ms);

%% ---- File boundary positions (for separator lines) ----
file_boundaries = [];
boundary_labels = {};
for i = 2:N
    if ~strcmp(files{i}, files{i-1})
        file_boundaries(end+1) = i - 0.5; %#ok<SAGROW>
        boundary_labels{end+1} = strrep(files{i}, '_', '\_'); %#ok<SAGROW>
    end
end

%% ---- Build file group index for box plots ----
file_idx = zeros(1, N);
file_labels_escaped = cellfun(@(s) strrep(s, '_', '\_'), unique_files, 'UniformOutput', false);
for i = 1:N
    file_idx(i) = find(strcmp(unique_files, files{i}));
end

%% ---- Figure 1 (2x1): Latency ----
figure(1);

% ---- Subplot 1: Stacked bar — per-trial latency breakdown ----
subplot(2,1,1);
stage_matrix = [upload_only_ms; overlap_ms; infer_gap_ms; download_only_ms]' / 1000;

h = bar(1:N, stage_matrix, 'stacked');
h(1).FaceColor = c_upload;
h(2).FaceColor = c_overlap;
h(3).FaceColor = c_infer;
h(4).FaceColor = c_download;

ax = gca;
xlabel('Trial');
ylabel('Latency (s)');
title('Per-Trial Latency Breakdown');
xticks(1:N);
ylim([0 max(sum(stage_matrix, 2)) * 1.15])
grid on; box off;
ax.YAxis.Color = 'none';
ax.YLabel.Color = txt_color;
ax.YAxis.TickLabelColor = txt_color;
ax.XGrid = 'off';
ax.YGrid = 'on';
hold on;
for bi = 1:numel(file_boundaries)
    xline(file_boundaries(bi), '--', 'Color', c_sep, 'LineWidth', 1, 'HandleVisibility', 'off');
end
hold off;
legend(h, {'Upload', 'Upload + Download', 'Inference wait', 'Download'}, ...
       'Location', 'best');

% ---- Subplot 2: Average stage proportion ----
subplot(2,1,2);
avg_up_only  = mean(upload_only_ms  ./ total_ms) * 100;
avg_overlap  = mean(overlap_ms      ./ total_ms) * 100;
avg_infer    = mean(infer_gap_ms    ./ total_ms) * 100;
avg_dl_only  = mean(download_only_ms./ total_ms) * 100;

b = bar([avg_up_only, avg_overlap, avg_infer, avg_dl_only]);
b.FaceColor = 'flat';
b.CData = [c_upload; c_overlap; c_infer; c_download];
ax = gca;
xticklabels({'Upload', 'Upload + Download', 'Inference', 'Download'});
ylabel('% of Total Pipeline');
ylim([0 max([avg_up_only, avg_overlap, avg_infer, avg_dl_only]) * 1.15]);
title('Average Stage Proportion');
grid on; box off;
ax.YAxis.Color = 'none';
ax.YLabel.Color = txt_color;
ax.YAxis.TickLabelColor = txt_color;
ax.XGrid = 'off';
ax.YGrid = 'on';

%% ---- Figure 2 (2x1): Speed ----
figure(2);

% ---- Subplot 3: Upload / download speed per trial ----
subplot(2,1,1);
h_up = plot(1:N, up_kbs, '-o', 'Color', c_ul_line, 'LineWidth', 1.5, 'MarkerSize', 5);
hold on;
h_dl = plot(1:N, dl_kbs, '-s', 'Color', c_dl_line, 'LineWidth', 1.5, 'MarkerSize', 5);
ax = gca;
xlabel('Trial');
ylabel('Speed (KB/s)');
title('Upload / Download Speed per Trial');
xticks(1:N);
grid on; box off;
ax.YAxis.Color = 'none';
ax.YLabel.Color = txt_color;
ax.YAxis.TickLabelColor = txt_color;
ax.XGrid = 'off';
ax.YGrid = 'on';
sep_handles_2 = gobjects(numel(file_boundaries), 1);
for bi = 1:numel(file_boundaries)
    sep_handles_2(bi) = xline(file_boundaries(bi), '--', 'Color', c_sep, 'LineWidth', 1);
end
legend([h_up, h_dl, sep_handles_2'], ...
       [{'Upload', 'Download'}, boundary_labels], ...
       'Location', 'best');
hold off;

% ---- Subplot 4: Speed variation box plot ----
subplot(2,1,2);

group_data = [up_kbs, dl_kbs]';
group_type = categorical([repmat({'Upload'}, 1, N), repmat({'Download'}, 1, N)])';

bc = boxchart(group_type, group_data);
bc.BoxFaceColor = [0.5 0.5 0.5];
bc.MarkerColor  = [0.5 0.5 0.5];

ax = gca;
cla;
hold on;
bc_up = boxchart(ones(N,1), up_kbs', 'BoxFaceColor', c_ul_line, 'MarkerColor', c_ul_line);
bc_dl = boxchart(2*ones(N,1), dl_kbs', 'BoxFaceColor', c_dl_line, 'MarkerColor', c_dl_line);
hold off;
ax.XTick = [1 2];
ax.XTickLabel = {'Upload', 'Download'};
ylabel('Speed (KB/s)');
title('Overall Speed Variation');
legend([bc_up, bc_dl], {'Upload', 'Download'}, 'Location', 'best');
grid on; box off;
ax.YAxis.Color = 'none';
ax.YLabel.Color = txt_color;
ax.YAxis.TickLabelColor = txt_color;
ax.XGrid = 'off';
ax.YGrid = 'on';
