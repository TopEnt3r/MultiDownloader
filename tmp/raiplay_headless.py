#!/usr/bin/env python3
"""
Headless RaiPlay search and download script for Java integration
Usage: python raiplay_headless.py <command> [args]
"""

import sys
import json
import os
import logging

# Disable ANSI colors completely
os.environ['NO_COLOR'] = '1'
os.environ['TERM'] = 'dumb'

# Suppress all logging to avoid contaminating JSON output
logging.basicConfig(level=logging.CRITICAL)
logging.getLogger().setLevel(logging.CRITICAL)

# Redirect warnings and errors to null
import warnings
warnings.filterwarnings("ignore")

# Redirect stderr and stdout to /dev/null temporarily during imports
import io
old_stderr = sys.stderr
old_stdout = sys.stdout
sys.stderr = io.StringIO()
sys.stdout = io.StringIO()

# Add parent directory to path for imports
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'Downloader', 'StreamingCommunity', 'StreamingCommunity-main'))

from StreamingCommunity.Api.Site.raiplay import (
    title_search,
    media_search_manager,
    download_film,
    download_series
)
from StreamingCommunity.Api.Site.raiplay.util.ScrapeSerie import GetSerieInfo
from StreamingCommunity.Api.Site.raiplay.series import download_video
import os as os_module

# Restore stderr and stdout
sys.stderr = old_stderr
sys.stdout = old_stdout

# Disable rich console globally by replacing console.print with a no-op
try:
    from rich.console import Console
    Console.print = lambda self, *args, **kwargs: None
except:
    pass

def cmd_search(query):
    """Search for titles on RaiPlay"""
    try:
        # Redirect stdout to suppress prints during search
        saved_stdout = sys.stdout
        sys.stdout = io.StringIO()
        
        count = title_search(query)
        
        # Restore stdout for JSON output
        sys.stdout = saved_stdout
        
        results = []
        for idx in range(media_search_manager.get_length()):
            media = media_search_manager.get(idx)
            
            # Extract attributes safely
            media_id = getattr(media, 'id', '')
            title = getattr(media, 'name', 'Unknown')
            media_type = getattr(media, 'type', 'tv')
            year = str(getattr(media, 'year', ''))
            path_id = getattr(media, 'path_id', '')
            url = getattr(media, 'url', '')
            
            results.append({
                'id': media_id,
                'title': title,
                'type': media_type,
                'year': year,
                'source': 'RaiPlay',
                'sourceAlias': 'RAI',
                'path_id': path_id,
                'url': url
            })
        
        print(json.dumps({
            'status': 'ok',
            'count': count,
            'results': results
        }))
        
    except Exception as e:
        print(json.dumps({
            'status': 'error',
            'message': str(e)
        }), file=sys.stderr)
        sys.exit(1)

def cmd_list_episodes(path_id):
    """List episodes for a series using path_id"""
    try:
        # Redirect stdout to suppress prints
        saved_stdout = sys.stdout
        saved_stderr = sys.stderr
        sys.stdout = io.StringIO()
        sys.stderr = io.StringIO()
        
        # Get episodes using GetSerieInfo
        serie_info = GetSerieInfo(path_id)
        serie_info.collect_info_title()
        
        # Load episodes for each season
        for season in serie_info.seasons_manager.seasons:
            serie_info.collect_info_season(season.number)
        
        # Restore stdout/stderr
        sys.stdout = saved_stdout
        sys.stderr = saved_stderr
        
        episodes = []
        
        # If no episodes found, return empty list (film or no content yet)
        if not hasattr(serie_info.seasons_manager, 'seasons') or len(serie_info.seasons_manager.seasons) == 0:
            print(json.dumps({
                'status': 'ok',
                'episodes': []
            }))
            return
        
        # seasons_manager.seasons is a List[Season]
        for idx, season in enumerate(serie_info.seasons_manager.seasons):
            season_num = season.number
            
            # season.episodes is an EpisodeManager with episodes attribute
            if hasattr(season.episodes, 'episodes'):
                for ep in season.episodes.episodes:
                    # Episode is an object with attributes, not a dict
                    ep_number = getattr(ep, 'number', '')
                    # Skip episodes without valid number
                    if not ep_number or str(ep_number).strip() == '':
                        continue
                    
                    episodes.append({
                        'id': getattr(ep, 'id', ''),
                        'season': str(season_num),
                        'episode': str(ep_number),
                        'title': getattr(ep, 'name', '')
                    })
        
        print(json.dumps({
            'status': 'ok',
            'episodes': episodes
        }))
        
    except Exception as e:
        print(json.dumps({
            'status': 'error',
            'message': str(e)
        }), file=sys.stderr)
        sys.exit(1)

def cmd_download_film(path_id, output_dir):
    """Download a film using path_id"""
    try:
        # Create output folder
        os_module.makedirs(output_dir, exist_ok=True)

        # Redirect stdout AND stderr to suppress all prints
        saved_stdout = sys.stdout
        saved_stderr = sys.stderr
        sys.stdout = io.StringIO()
        sys.stderr = io.StringIO()

        # Download film - create MediaItem with path_id
        # download_film expects a MediaItem object
        class FakeMediaItem:
            def __init__(self, pid):
                self.path_id = pid

        fake_item = FakeMediaItem(path_id)

        # Change to output directory before download
        original_dir = os_module.getcwd()
        os_module.chdir(output_dir)

        try:
            # Call download_film with MediaItem
            path, stopped = download_film(fake_item)

            # Verify file actually exists and is non-empty
            if not path or not os_module.path.exists(path):
                raise Exception("Download failed: file not created")
            size = os_module.path.getsize(path)
            if size == 0:
                raise Exception("Download failed: file is empty")

            if stopped:
                raise Exception("Download was stopped")
            
            # Move file to output_dir with simple name
            ext = os_module.path.splitext(path)[1]  # .mkv or .mp4
            # Get film name from path (filename without extension)
            film_name = os_module.path.splitext(os_module.path.basename(path))[0]
            new_filename = f"{film_name}{ext}"
            new_path = os_module.path.join(output_dir, new_filename)
            
            # Move file to final destination
            import shutil
            shutil.move(path, new_path)
            
            # Clean up empty directories left behind
            try:
                parent = os_module.path.dirname(path)
                while parent and parent != output_dir:
                    if os_module.path.isdir(parent) and not os_module.listdir(parent):
                        os_module.rmdir(parent)
                    parent = os_module.path.dirname(parent)
            except:
                pass  # Ignore cleanup errors

        finally:
            # Always restore directory
            os_module.chdir(original_dir)
            # Restore stdout/stderr
            sys.stdout = saved_stdout
            sys.stderr = saved_stderr

        print(json.dumps({
            'status': 'ok',
            'message': 'Download completed',
            'file': new_path
        }))
        
    except Exception as e:
        print(json.dumps({
            'status': 'error',
            'message': str(e)
        }), file=sys.stderr)
        sys.exit(1)

def cmd_download_episode(path_id, season, episode, output_dir):
    """Download a specific episode using path_id"""
    try:
        # Validate inputs
        if not season or not episode:
            raise ValueError(f"Invalid season or episode number: season='{season}', episode='{episode}'")

        # Create output folder
        os_module.makedirs(output_dir, exist_ok=True)

        # Redirect stdout AND stderr to suppress all prints
        saved_stdout = sys.stdout
        saved_stderr = sys.stderr
        sys.stdout = io.StringIO()
        sys.stderr = io.StringIO()

        # Change to output directory before download
        original_dir = os_module.getcwd()
        os_module.chdir(output_dir)

        try:
            # Get series info
            serie_info = GetSerieInfo(path_id)
            serie_info.collect_info_title()
            series_name = serie_info.series_name

            # Load episodes for the season
            season_int = int(season)
            episode_int = int(episode)
            serie_info.collect_info_season(season_int)

            # Download the specific episode directly using library helper
            path, stopped = download_video(season_int, episode_int, serie_info)

            # Verify file actually exists and is non-empty
            if not path or not os_module.path.exists(path):
                raise Exception("Download failed: file not created")
            size = os_module.path.getsize(path)
            if size == 0:
                raise Exception("Download failed: file is empty")

            if stopped:
                raise Exception("Download was stopped")
            
            # Move and rename file to output_dir with correct format
            # Format: "Series Name 01x02.ext"
            ext = os_module.path.splitext(path)[1]  # .mkv or .mp4
            new_filename = f"{series_name} {str(season_int).zfill(2)}x{str(episode_int).zfill(2)}{ext}"
            new_path = os_module.path.join(output_dir, new_filename)
            
            # Move file to final destination
            import shutil
            shutil.move(path, new_path)
            
            # Clean up empty directories left behind
            try:
                parent = os_module.path.dirname(path)
                while parent and parent != output_dir:
                    if os_module.path.isdir(parent) and not os_module.listdir(parent):
                        os_module.rmdir(parent)
                    parent = os_module.path.dirname(parent)
            except:
                pass  # Ignore cleanup errors

        finally:
            # Always restore directory
            os_module.chdir(original_dir)
            # Restore stdout/stderr
            sys.stdout = saved_stdout
            sys.stderr = saved_stderr

        print(json.dumps({
            'status': 'ok',
            'message': 'Download completed',
            'file': new_path
        }))
        
    except Exception as e:
        print(json.dumps({
            'status': 'error',
            'message': str(e)
        }), file=sys.stderr)
        sys.exit(1)

def main():
    if len(sys.argv) < 2:
        print(json.dumps({
            'status': 'error',
            'message': 'Usage: raiplay_headless.py <command> [args]'
        }), file=sys.stderr)
        sys.exit(1)
    
    command = sys.argv[1]
    
    if command == 'search':
        if len(sys.argv) < 3:
            print(json.dumps({'status': 'error', 'message': 'Missing query'}), file=sys.stderr)
            sys.exit(1)
        cmd_search(sys.argv[2])
    
    elif command == 'list-episodes':
        if len(sys.argv) < 3:
            print(json.dumps({'status': 'error', 'message': 'Missing path_id'}), file=sys.stderr)
            sys.exit(1)
        cmd_list_episodes(sys.argv[2])
    
    elif command == 'download-film':
        if len(sys.argv) < 4:
            print(json.dumps({'status': 'error', 'message': 'Missing path_id or output_dir'}), file=sys.stderr)
            sys.exit(1)
        cmd_download_film(sys.argv[2], sys.argv[3])
    
    elif command == 'download-episode':
        if len(sys.argv) < 6:
            print(json.dumps({'status': 'error', 'message': 'Missing path_id, season, episode, or output_dir'}), file=sys.stderr)
            sys.exit(1)
        cmd_download_episode(sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5])
    
    else:
        print(json.dumps({
            'status': 'error',
            'message': f'Unknown command: {command}'
        }), file=sys.stderr)
        sys.exit(1)

if __name__ == '__main__':
    main()
